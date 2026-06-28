package me.rerere.workspace

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

interface WorkspaceShellRunner {
    /** 在协程上下文中执行 shell 命令。实现必须确保所有阻塞 IO 不在主线程执行。 */
    suspend fun execute(context: WorkspaceShellContext): WorkspaceCommandResult
}

data class WorkspaceShellContext(
    val root: String,
    val command: String,
    val cwd: String,
    val filesDir: File,
    val linuxDir: File,
    val tempDir: File,
    val workingDir: File,
    val timeoutMillis: Long,
    val stdin: ByteArray? = null,
)

class HostShellRunner : WorkspaceShellRunner {
    override suspend fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val process = ProcessBuilder(defaultShell(), "-c", context.command)
            .directory(context.workingDir)
            .redirectErrorStream(false)
            .start()
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    private fun defaultShell(): String =
        if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
}

// 单个流保留的最大字符数, 防止命令疯狂输出导致 OOM 或撑爆 LLM 上下文
const val MAX_OUTPUT_CHARS = 128 * 1024

/**
 * 等待进程结束并收集输出。
 *
 * 这是一个 suspend 但同步阻塞的函数（类似 runInterruptible 的行为模式）。
 * waitFor(long, TimeUnit) 响应 InterruptedException，因此当调用方通过
 * runInterruptible(Dispatchers.IO) 包裹时，协程取消能正确中断 waitFor 并触发进程销毁。
 *
 * 调用方（通常是 WorkspaceRepository）必须确保此函数运行在 IO 调度器上。
 */
suspend fun Process.readResult(timeoutMillis: Long, stdin: ByteArray? = null): WorkspaceCommandResult {
    val stdout = StreamCollector(inputStream)
    val stderr = StreamCollector(errorStream)
    val stdinWriter = stdin?.let { bytes -> StreamWriter(outputStream, bytes) }
    try {
        val finished = waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            destroyForcibly()
            closeStreamsQuietly(inputStream, errorStream, outputStream)
        }
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        return WorkspaceCommandResult(
            exitCode = if (finished) exitValue() else -1,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = !finished,
            truncated = stdout.truncated || stderr.truncated,
        )
    } catch (e: InterruptedException) {
        // 调用方线程被中断（如协程取消时的 runInterruptible），杀掉进程避免命令继续执行
        destroyForcibly()
        closeStreamsQuietly(inputStream, errorStream, outputStream)
        // 进程被杀且流关闭后，阻塞在读/写中的采集线程会因 IOException 退出，
        // 这里 join 回收两个采集线程，避免每次取消泄漏一对线程
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        throw e
    } catch (e: Throwable) {
        // 兜底：任何未预期的异常都确保进程被销毁、流被关闭、采集线程被回收
        destroyForcibly()
        closeStreamsQuietly(inputStream, errorStream, outputStream)
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        throw e
    }
}

/** 静默关闭一批 Closeable，忽略关闭时的异常 */
private fun closeStreamsQuietly(vararg streams: Closeable?) {
    for (stream in streams) {
        try {
            stream?.close()
        } catch (_: IOException) {
            // 忽略关闭时的异常
        }
    }
}

private class StreamWriter(
    private val stream: java.io.OutputStream,
    private val bytes: ByteArray,
) {
    private val thread = Thread {
        try {
            stream.use { output ->
                output.write(bytes)
                output.flush()
            }
        } catch (_: IOException) {
            // 子进程提前退出或被强杀时 stdin 可能关闭, 忽略即可, 退出状态会由进程本身返回
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)
}

private class StreamCollector(
    stream: InputStream,
    private val maxChars: Int = MAX_OUTPUT_CHARS,
) {
    private val builder = StringBuilder()

    @Volatile
    var truncated = false
        private set

    private val thread = Thread {
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    // 超出上限后继续读到 EOF 并丢弃，否则管道写满会阻塞子进程导致其无法退出
                    synchronized(builder) {
                        val remaining = maxChars - builder.length
                        if (remaining > 0) {
                            builder.append(buffer, 0, minOf(read, remaining))
                        }
                        if (read > remaining) {
                            truncated = true
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // 进程被强杀（超时/取消）时流会被关闭，阻塞中的 read 会抛 InterruptedIOException 等，
            // 保留已读取的内容即可；不能让异常逃逸，否则会触发线程默认异常处理导致应用崩溃
        }
    }.apply {
        // 设为 daemon: 即使 proot grandchild 残留 fd 导致 read() 永久阻塞, 也不会阻止 JVM 退出
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)

    fun text(): String = synchronized(builder) { builder.toString() }
}

package me.rerere.rikkahub

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 验证 ChatService 中流式 chunk 已正确加入 conflate 节流。
 *
 * 根因：handleMessageComplete() 中 collect GenerationChunk 时，
 * 每个 chunk（10-50ms一次）都在主线程执行 updateConversation()，
 * 无任何节流，导致 Compose 频繁重组造成卡顿。
 *
 * 修复：在 collect 之前加入 .conflate()，主线程忙时自动丢弃中间 chunk，
 * 只保留最新的进行处理，减少不必要的重组。
 */
class ChatServiceConflateTest {

    companion object {
        private val CHAT_SERVICE_PATH = "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt"
    }

    @Test
    fun `conflate import should exist in ChatService`() {
        val source = readChatService()
        assertTrue(
            "ChatService.kt 必须导入 kotlinx.coroutines.flow.conflate",
            source.contains("import kotlinx.coroutines.flow.conflate")
        )
    }

    @Test
    fun `conflate should be called before collect in handleMessageComplete`() {
        val source = readChatService()

        // 验证 .conflate() 出现在 .collect 之前
        val conflateIndex = source.indexOf(".conflate()")
        val collectIndex = source.indexOf(".collect { chunk ->")

        assertTrue(
            "必须存在 .conflate() 调用",
            conflateIndex >= 0
        )
        assertTrue(
            "必须存在 .collect { chunk -> 调用",
            collectIndex >= 0
        )
        assertTrue(
            ".conflate() 必须在 .collect 之前（conflate 在 $conflateIndex，collect 在 $collectIndex）",
            conflateIndex < collectIndex
        )
    }

    @Test
    fun `conflate should be placed between onCompletion and collect`() {
        val source = readChatService()

        // 验证正确的位置关系：onCompletion 区块结束后紧接着 conflate
        val lines = source.lines()
        var foundOnCompletionEnd = false
        var foundConflate = false
        var foundCollect = false
        var conflateLineIndex = -1
        var collectLineIndex = -1

        for ((index, line) in lines.withIndex()) {
            when {
                line.trimStart().startsWith("}.onCompletion {") -> {
                    foundOnCompletionEnd = true
                }
                line.trimStart().startsWith("}.conflate()") -> {
                    foundConflate = true
                    conflateLineIndex = index
                }
                line.trimStart().startsWith(".collect {") -> {
                    foundCollect = true
                    collectLineIndex = index
                }
            }
        }

        assertTrue("onCompletion 区块必须存在", foundOnCompletionEnd)
        assertTrue(
            "必须在 onCompletion 后的下一个调用链上找到 .conflate()",
            foundConflate
        )
        assertTrue(
            "必须有 .collect 调用",
            foundCollect
        )
        assertTrue(
            ".conflate() 必须在 .collect 之前（conflate 在第 ${conflateLineIndex + 1} 行，collect 在第 ${collectLineIndex + 1} 行）",
            conflateLineIndex < collectLineIndex
        )
    }

    private fun readChatService(): String {
        val workspaceRoot = File("").absoluteFile // 假设工作目录在 project root
        val file = findProjectRoot(workspaceRoot).resolve(CHAT_SERVICE_PATH)
        assertTrue("ChatService.kt 未找到: ${file.absolutePath}", file.exists())
        return file.readText()
    }

    /**
     * 从当前目录向上查找 project root（包含 app/build.gradle.kts 的目录）
     */
    private fun findProjectRoot(start: File): File {
        var current = start
        while (current != null) {
            if (File(current, "app/build.gradle.kts").exists()) {
                return current
            }
            current = current.parentFile
        }
        return start
    }
}

package me.rerere.common.http

import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException

/**
 * 代表 SSE 连接中的各种事件
 */
sealed class SseEvent {
    /**
     * 连接成功打开
     */
    data object Open : SseEvent()

    /**
     * 收到一个具体事件
     * @param id 事件ID
     * @param type 事件类型
     * @param data 事件数据
     */
    data class Event(val id: String?, val type: String?, val data: String) : SseEvent()

    /**
     * 连接被关闭
     */
    data object Closed : SseEvent()

    /**
     * 发生错误
     * @param throwable 异常信息（可能为null）
     * @param response 错误时的响应（可能为null）
     * @param isRecoverable 是否为可恢复错误。true 表示连接层可自动重试的瞬时错误（网络超时、5xx），
     *                      上游收到后可决定是否重试；false 表示不可恢复错误（4xx、协议错误）。
     */
    data class Failure(
        val throwable: Throwable?,
        val response: Response?,
        val isRecoverable: Boolean = false
    ) : SseEvent()
}

/**
 * 安全发送：优先非阻塞 trySend，仅在下游消费不及时导致 channel 满时阻塞当前线程以施加背压。
 *
 * 使用场景：从非协程回调（OkHttp 线程）向 Flow 发送事件。典型 callbackFlow 模式中，
 * trySend 在 channel 满时返回 false 并静默丢事件，本函数确保事件不丢失：
 * - 正常情况：trySend 成功返回（零开销）
 * - 背压情况：runBlocking { send(...) } 阻塞回调线程，让下游慢消费自然回压到 HTTP 层
 * - 已关闭情况：channel 已关闭时静默跳过（Flow 终结中，丢失已无意义）
 */
private fun <E> SendChannel<E>.safeSend(element: E) {
    if (trySend(element).isFailure && !isClosedForSend) {
        // Channel 满且未关闭 → 阻塞回调线程等待下游消费，实现真实背压
        runBlocking {
            try {
                send(element)
            } catch (_: ClosedSendChannelException) {
                // Channel 在等待期间被关闭（如 Flow 被取消），事件正常丢失
            }
        }
    }
}

/**
 * 判断 SSE 连接的失败是否可恢复（瞬时性/可重试）。
 *
 * 可恢复场景：
 * - IOException：网络超时、DNS 解析失败、连接重置等瞬时网络错误
 * - HTTP 5xx：服务端临时过载或内部错误，可能重试成功
 *
 * 不可恢复场景：
 * - HTTP 4xx（不含 429 — 但此处保守归为不可恢复，让上游按需处理）：
 *   鉴权失败、参数错误等，重试无意义
 * - 其他未知异常
 */
private fun isRecoverableError(t: Throwable?, response: Response?): Boolean {
    // IOException 通常是网络瞬时问题，可重试
    if (t is IOException) return true
    // 5xx 服务端临时错误，可重试
    if (response != null && response.code in 500..599) return true
    return false
}


/**
 * 为 OkHttpClient 创建 SSE (Server-Sent Events) 连接的扩展函数
 *
 * 将 OkHttp 的 EventSource 封装成 Kotlin Flow，提供响应式的 SSE 事件流
 *
 * @param request HTTP 请求，用于建立 SSE 连接
 * @return Flow<SseEvent> 包含 SSE 事件的响应式流
 */
fun OkHttpClient.sseFlow(request: Request): Flow<SseEvent> {
    return callbackFlow {
        // 1. 创建 EventSourceListener
        // 监听 SSE 连接的各种事件并转换为 Flow 事件
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                // 从回调中安全地发送事件到 Flow
                // 连接成功建立时触发
                safeSend(SseEvent.Open)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                // 收到服务器发送的数据事件时触发
                // 将事件数据封装后发送到 Flow
                // 使用 safeSend 确保流式数据不因背压静默丢失
                safeSend(SseEvent.Event(id, type, data))
            }

            override fun onClosed(eventSource: EventSource) {
                // 连接正常关闭时触发
                safeSend(SseEvent.Closed)
                channel.close() // 关闭 Flow 通道
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // 连接发生错误时触发
                val isRecoverable = isRecoverableError(t, response)
                safeSend(SseEvent.Failure(t, response, isRecoverable))

                if (isRecoverable) {
                    // 可恢复错误：channel 正常关闭（不抛异常），
                    // 让上游收到 SseEvent.Failure 后自行决定是否重试
                    channel.close()
                } else {
                    // 不可恢复错误：以异常关闭，Flow collect 处会收到此异常
                    channel.close(t)
                }
            }
        }

        // 2. 创建 EventSource
        // 使用当前 OkHttpClient 创建 EventSource 工厂
        val factory = EventSources.createFactory(this@sseFlow)
        val eventSource = factory.newEventSource(request, listener)

        // 3. awaitClose 用于在 Flow 被取消时执行清理操作
        // 当收集 Flow 的协程被取消时，这个块会被调用
        awaitClose {
            // 关闭 SSE 连接，释放资源
            eventSource.cancel()
        }
    }
}

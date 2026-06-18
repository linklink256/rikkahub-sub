package me.rerere.ai.util

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "ErrorParser"

class HttpException(
    message: String
) : RuntimeException(message)

fun JsonElement.parseErrorDetail(): HttpException {
    return when (this) {
        is JsonObject -> {
            // 尝试获取常见的错误字段
            val errorFields = listOf("error", "detail", "message", "description")

            // 查找第一个存在的错误字段
            val foundField = errorFields.firstOrNull { this[it] != null }

            if (foundField != null) {
                // 递归解析找到的字段值
                this[foundField]!!.parseErrorDetail()
            } else {
                // 如果没有找到任何错误字段，序列化整个对象
                HttpException(Json.encodeToString(JsonElement.serializer(), this))
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                HttpException("Unknown error: Empty JSON array")
            } else {
                // 递归解析数组的第一个元素
                this.first().parseErrorDetail()
            }
        }

        is JsonPrimitive -> {
            // 对于基本类型，直接使用其内容
            HttpException(this.jsonPrimitive.content)
        }

        else -> {
            // 其他情况，序列化整个元素
            HttpException(Json.encodeToString(JsonElement.serializer(), this))
        }
    }
}

/**
 * 安全地从 HTTP 错误响应体构造异常。
 *
 * 当服务端返回非 JSON 内容时（如 HTML 错误页、CDN 拦截页、429 限流页、502 网关页），
 * 不会抛出 [kotlinx.serialization.json.JsonDecodingException]（此前会用 JSON 解析错误
 * 覆盖掉原始的 IO/网络异常），而是构造一个包含响应体摘要的有意义异常，
 * 并保留 [originalException] 作为 cause。
 *
 * @param bodyRaw            HTTP 响应体原始字符串（可能为 null / 空 / HTML / JSON）
 * @param originalException  触发此次错误处理的原始异常（如 IOException: byteCount < 0）；
 *                           为 null 时表示仅由响应体触发
 * @return 包含错误详情的异常
 */
fun parseErrorBody(
    bodyRaw: String?,
    originalException: Throwable? = null,
): Throwable {
    if (bodyRaw.isNullOrBlank()) {
        return originalException
            ?: HttpException("Unknown error: empty response body")
    }

    return try {
        Json.parseToJsonElement(bodyRaw).parseErrorDetail()
    } catch (e: Throwable) {
        // 响应体不是合法 JSON（如 HTML 错误页 / CDN 拦截页 / 限流页）
        Log.w(TAG, "parseErrorBody: non-JSON response (${bodyRaw.length} bytes), preview: ${bodyRaw.take(200)}")
        val preview = bodyRaw.take(500).replace("\n", " ").replace("\r", " ").trim()
        val cause = originalException ?: e
        HttpException(
            "Server returned non-JSON response (${bodyRaw.length} bytes): $preview",
        ).also { it.initCause(cause) }
    }
}

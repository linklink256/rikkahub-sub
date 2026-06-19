package me.rerere.common.android

/**
 * 日志脱敏工具：在把 [LogEntry]（尤其是请求日志）交给 AI 查看前，
 * 隐藏其中可能携带的凭证（Authorization / API Key / Cookie 等），
 * 避免把本地 App 的密钥发送给云端模型。
 */

/** 敏感请求/响应头名称（小写比较），值在返回给 AI 前会被替换为 [REDACTED]。 */
private val SENSITIVE_HEADER_NAMES = setOf(
    "authorization",
    "proxy-authorization",
    "x-api-key",
    "api-key",
    "x-goog-api-key",
    "anthropic-api-key",
    "cookie",
    "set-cookie",
    "x-amz-security-token",
)

private const val REDACTED = "***REDACTED***"

/** 对敏感请求/响应头脱敏，其余原样保留。 */
fun redactHeaders(headers: Map<String, String>): Map<String, String> =
    headers.mapValues { (key, value) ->
        if (key.lowercase() in SENSITIVE_HEADER_NAMES) REDACTED else value
    }

/** 匹配 JSON body 中常见敏感字段名，将其值替换为 [REDACTED]（大小写不敏感）。 */
private val SENSITIVE_BODY_PATTERNS = listOf(
    Regex(
        """("(?:api[_-]?key|apikey|authorization|token|secret|access[_-]?token)"\s*:\s*)"[^"]*""",
        RegexOption.IGNORE_CASE,
    )
)

/** 对请求/响应 body 中的敏感凭证做正则脱敏。 */
fun redactSecrets(text: String): String {
    var result = text
    for (pattern in SENSITIVE_BODY_PATTERNS) {
        result = pattern.replace(result) { match ->
            match.groupValues[1] + "\"" + REDACTED + "\""
        }
    }
    return result
}

/** 返回该日志条目脱敏后的副本（[LogEntry.TextLog] 不含敏感信息，原样返回）。 */
fun LogEntry.redacted(): LogEntry = when (this) {
    is LogEntry.TextLog -> this
    is LogEntry.RequestLog -> copy(
        requestHeaders = redactHeaders(requestHeaders),
        responseHeaders = redactHeaders(responseHeaders),
        requestBody = requestBody?.let(::redactSecrets),
    )
}

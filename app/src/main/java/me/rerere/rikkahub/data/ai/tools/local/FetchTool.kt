package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.asToolResult
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.add

internal fun fetchTool(): Tool = Tool(
    name = "fetch",
    description = """
        Fetch content from a URL via a simple HTTP GET request.
        Use this to retrieve raw text content from APIs, raw files, or simple web pages.
        For complex web pages with JavaScript rendering, use scrape_web instead.
        Returns the response body as text (truncated to 50000 chars), along with status code and content type.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The URL to fetch (must start with http:// or https://)")
                })
                put("method", buildJsonObject {
                    put("type", "string")
                    put("description", "HTTP method: GET (default) or POST")
                    put("enum", buildJsonArray {
                        add("GET")
                        add("POST")
                    })
                })
                put("headers", buildJsonObject {
                    put("type", "object")
                    put("description", "Optional HTTP headers as key-value pairs")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "Request body for POST requests")
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timeout in seconds (default 30, max 60)")
                })
            },
            required = listOf("url"),
        )
    },
    execute = {
        val params = it.jsonObject
        val urlStr = params["url"]?.jsonPrimitive?.contentOrNull
            ?: error("url is required")
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            error("URL must start with http:// or https://")
        }
        val method = params["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        val timeoutSec = (params["timeout"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: 30).coerceIn(1, 60)
        val headersJson = params["headers"]?.let { h ->
            h as? kotlinx.serialization.json.JsonObject
        }
        val body = params["body"]?.jsonPrimitive?.contentOrNull

        val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutSec * 1000
            readTimeout = timeoutSec * 1000
            instanceFollowRedirects = true
            headersJson?.forEach { (key, value) ->
                val v = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
                setRequestProperty(key, v)
            }
            if (body != null) {
                doOutput = true
                outputStream.write(body.toByteArray(Charsets.UTF_8))
                outputStream.close()
            }
        }

        val payload = try {
            val code = connection.responseCode
            val contentType = connection.contentType ?: ""
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val full = reader.readText()
                if (full.length > 50000) full.take(50000) + "\n... (truncated)" else full
            } ?: ""
            buildJsonObject {
                put("status", code)
                put("contentType", contentType)
                put("url", connection.url.toString())
                put("body", text)
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("status", -1)
                put("error", e.message ?: e::class.simpleName ?: "unknown error")
            }
        } finally {
            connection.disconnect()
        }
        payload.asToolResult()
    }
)

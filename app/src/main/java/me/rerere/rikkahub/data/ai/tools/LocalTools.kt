package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.common.android.redacted
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText
import java.time.ZonedDateTime
import java.net.HttpURLConnection
import java.net.URL
import java.time.format.TextStyle
import java.util.Locale

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("ask_btw")
    data object AskBtw : LocalToolOption()

    @Serializable
    @SerialName("fetch")
    data object Fetch : LocalToolOption()

    @Serializable
    @SerialName("logs")
    data object Logs : LocalToolOption()
}

class LocalTools(private val context: Context, private val eventBus: AppEventBus) {
    val javascriptTool by lazy {
        Tool(
            name = "eval_javascript",
            description = """
                Execute JavaScript code using QuickJS engine (ES2020).
                The result is the value of the last expression in the code.
                For calculations with decimals, use toFixed() to control precision.
                Console output (log/info/warn/error) is captured and returned in 'logs' field.
                No DOM or Node.js APIs available.
                Example: '1 + 2' returns 3; 'const x = 5; x * 2' returns 10.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("code", buildJsonObject {
                            put("type", "string")
                            put("description", "The JavaScript code to execute")
                        })
                    },
                    required = listOf("code")
                )
            },
            execute = {
                val logs = arrayListOf<String>()
                val context = QuickJSContext.create()
                context.setConsole(object : QuickJSContext.Console {
                    override fun log(info: String?) {
                        logs.add("[LOG] $info")
                    }

                    override fun info(info: String?) {
                        logs.add("[INFO] $info")
                    }

                    override fun warn(info: String?) {
                        logs.add("[WARN] $info")
                    }

                    override fun error(info: String?) {
                        logs.add("[ERROR] $info")
                    }
                })
                val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull
                val result = context.evaluate(code)
                val payload = buildJsonObject {
                    if (logs.isNotEmpty()) {
                        put("logs", JsonPrimitive(logs.joinToString("\n")))
                    }
                    put(
                        key = "result",
                        element = when (result) {
                            null -> JsonNull
                            is QuickJSObject -> JsonPrimitive(result.stringify())
                            else -> JsonPrimitive(result.toString())
                        }
                    )
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val timeTool by lazy {
        Tool(
            name = "get_time_info",
            description = """
                Get the current local date and time info from the device.
                Returns year/month/day, weekday, ISO date/time strings, timezone, and timestamp.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject { }
                )
            },
            execute = {
                val now = ZonedDateTime.now()
                val date = now.toLocalDate()
                val time = now.toLocalTime().withNano(0)
                val weekday = now.dayOfWeek
                val payload = buildJsonObject {
                    put("year", date.year)
                    put("month", date.monthValue)
                    put("day", date.dayOfMonth)
                    put("weekday", weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                    put("weekday_en", weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                    put("weekday_index", weekday.value)
                    put("date", date.toString())
                    put("time", time.toString())
                    put("datetime", now.withNano(0).toString())
                    put("timezone", now.zone.id)
                    put("utc_offset", now.offset.id)
                    put("timestamp_ms", now.toInstant().toEpochMilli())
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val clipboardTool by lazy {
        Tool(
            name = "clipboard_tool",
            description = """
                Read or write plain text from the device clipboard.
                Use action: read or write. For write, provide text.
                Do NOT write to the clipboard unless the user has explicitly requested it.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                kotlinx.serialization.json.buildJsonArray {
                                    add("read")
                                    add("write")
                                }
                            )
                            put("description", "Operation to perform: read or write")
                        })
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "Text to write to the clipboard (required for write)")
                        })
                    },
                    required = listOf("action")
                )
            },
            execute = {
                val params = it.jsonObject
                val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
                when (action) {
                    "read" -> {
                        val payload = buildJsonObject {
                            put("text", context.readClipboardText())
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    }

                    "write" -> {
                        val text = params["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
                        context.writeClipboardText(text)
                        val payload = buildJsonObject {
                            put("success", true)
                            put("text", text)
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    }

                    else -> error("unknown action: $action, must be one of [read, write]")
                }
            }
        )
    }

    val ttsTool by lazy {
        Tool(
            name = "text_to_speech",
            description = """
                Speak text aloud to the user using the device's text-to-speech engine.
                Use this when the user asks you to read something aloud, or when audio output is appropriate.
                The tool returns immediately; audio plays in the background on the device.
                Provide natural, readable text without markdown formatting.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "The text to speak aloud")
                        })
                    },
                    required = listOf("text")
                )
            },
            execute = {
                val text = it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    ?: error("text is required")
                eventBus.emit(AppEvent.Speak(text))
                val payload = buildJsonObject {
                    put("success", true)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val askUserTool by lazy {
        Tool(
            name = "ask_user",
            description = """
                Ask the user one or more questions when you need clarification, additional information, or confirmation.
                Each question can optionally provide a list of suggested options for the user to choose from.
                The user may select an option or provide their own free-text answer for each question.
                The answers will be returned as a JSON object mapping question IDs to the user's responses.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("questions", buildJsonObject {
                            put("type", "array")
                            put("description", "List of questions to ask the user")
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("id", buildJsonObject {
                                        put("type", "string")
                                        put("description", "Unique identifier for this question")
                                    })
                                    put("question", buildJsonObject {
                                        put("type", "string")
                                        put("description", "The question text to display to the user")
                                    })
                                    put("options", buildJsonObject {
                                        put("type", "array")
                                        put(
                                            "description",
                                            "Optional list of suggested options for the user to choose from"
                                        )
                                        put("items", buildJsonObject {
                                            put("type", "string")
                                        })
                                    })
                                    put("selection_type", buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "enum",
                                            kotlinx.serialization.json.buildJsonArray {
                                                add("text")
                                                add("single")
                                                add("multi")
                                            }
                                        )
                                        put(
                                            "description",
                                            "Answer type: text (free text input, default), single (select exactly one option), multi (select one or more options)"
                                        )
                                    })
                                })
                                put("required", kotlinx.serialization.json.buildJsonArray {
                                    add("id")
                                    add("question")
                                })
                            })
                        })
                    },
                    required = listOf("questions")
                )
            },
            needsApproval = { true },
            execute = {
                error("ask_user tool should be handled by HITL flow")
            }
        )
    }

    val fetchTool by lazy {
        Tool(
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
                        val sb = StringBuilder()
                        var total = 0
                        val buf = CharArray(8192)
                        while (true) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 50000) {
                                sb.append(buf, 0, n)
                                break
                            }
                            sb.append(buf, 0, n)
                        }
                        val result = sb.toString()
                        if (total > 50000) result + "\n... (truncated)" else result
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
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val logsTool by lazy {
        Tool(
            name = "get_logs",
            description = """
                Retrieve the app's recent runtime logs, including AI HTTP request logs and text logs.
                Use this to inspect the requests the app made to AI providers (URL, method, status code,
                duration, errors) and general app log messages — helpful for debugging issues the user
                is experiencing. Sensitive headers (Authorization / API keys / cookies) are redacted.
                Optional 'type' filters logs: "all" (default), "request", or "text".
                Optional 'limit' caps the number of returned entries (default 20, max 100).
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("type", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("all")
                                add("request")
                                add("text")
                            })
                            put("description", "Filter logs by type: all (default), request, or text")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Max number of log entries to return (default 20, max 100)")
                        })
                    }
                )
            },
            execute = {
                val params = it.jsonObject
                val type = params["type"]?.jsonPrimitive?.contentOrNull ?: "all"
                val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?.coerceIn(1, 100) ?: 20

                val allLogs: List<LogEntry> = when (type) {
                    "request" -> Logging.getRequestLogs()
                    "text" -> Logging.getTextLogs()
                    else -> Logging.getRecentLogs()
                }
                val selected = allLogs.take(limit).map { it.redacted() }

                val payload = buildJsonObject {
                    put("count", selected.size)
                    put("totalAvailable", allLogs.size)
                    put("requestLoggingEnabled", Logging.isRequestLoggingEnabled())
                    put(
                        "logs",
                        JsonInstant.encodeToJsonElement(ListSerializer(LogEntry.serializer()), selected)
                    )
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    fun getTools(options: List<LocalToolOption>): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.Fetch)) {
            tools.add(fetchTool)
        }
        if (options.contains(LocalToolOption.Logs)) {
            tools.add(logsTool)
        }
        return tools
    }
}

package me.rerere.rikkahub.data.ai.tools

import android.content.ContentUris
import android.content.ContentValues
import android.Manifest
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
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
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
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

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()
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
            """.trimIndent().replace("\\n", " "),
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

    val screenTimeTool by lazy {
        Tool(
            name = "get_screen_time",
            description = """
                Get the user's app screen usage (screen time) over a time range.
                Specify a custom interval with 'begin'/'end', or use the 'range' preset (today/week).
                Returns the total foreground time and a per-app breakdown sorted by usage time (descending).
                The device timezone is '${ZoneId.systemDefault()}' (UTC offset ${OffsetDateTime.now().offset});
                times without an explicit offset are interpreted in this timezone.
                Requires the 'Usage access' special permission; if it is not granted, the device's usage
                access settings page is opened automatically and an error is returned.
            """.trimIndent().replace("\\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("begin", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Start time (inclusive). Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                                    "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds. " +
                                    "When provided, 'range' is ignored."
                            )
                        })
                        put("end", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "End time (exclusive), same formats as 'begin'. Defaults to now."
                            )
                        })
                        put("range", buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                buildJsonArray {
                                    add("today")
                                    add("week")
                                }
                            )
                            put(
                                "description",
                                "Convenience preset, used only when 'begin' is omitted: today or week. Default today."
                            )
                        })
                        put("top", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum number of top apps to return, sorted by usage time. Default 10.")
                        })
                    }
                )
            },
            execute = {
                if (!context.hasUsageStatsPermission()) {
                    eventBus.emit(AppEvent.OpenUsageAccessSettings)
                    val payload = buildJsonObject {
                        put("error", "NO_PERMISSION")
                        put(
                            "message",
                            "Usage access permission is not granted. The system settings page has been " +
                                "opened; please ask the user to enable 'Usage access' for this app and try again."
                        )
                    }
                    return@Tool listOf(UIMessagePart.Text(payload.toString()))
                }

                val params = it.jsonObject
                val top = params["top"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 50) ?: 10

                val now = ZonedDateTime.now()
                val zone = now.zone
                val beginRaw = params["begin"]?.jsonPrimitive?.contentOrNull
                val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
                val rangePreset = params["range"]?.jsonPrimitive?.contentOrNull ?: "today"

                val startTime: ZonedDateTime
                val endTime: ZonedDateTime
                try {
                    endTime = endRaw?.let { raw -> parseUsageTime(raw, zone) } ?: now
                    startTime = if (beginRaw != null) {
                        parseUsageTime(beginRaw, zone)
                    } else when (rangePreset) {
                        "week" -> now.minusDays(7)
                        else -> now.toLocalDate().atStartOfDay(zone)
                    }
                } catch (e: Exception) {
                    val payload = buildJsonObject {
                        put("error", "INVALID_TIME")
                        put("message", e.message ?: "Invalid time format for begin/end.")
                    }
                    return@Tool listOf(UIMessagePart.Text(payload.toString()))
                }

                if (!startTime.isBefore(endTime)) {
                    val payload = buildJsonObject {
                        put("error", "INVALID_RANGE")
                        put("message", "begin must be earlier than end.")
                    }
                    return@Tool listOf(UIMessagePart.Text(payload.toString()))
                }

                val endMs = endTime.toInstant().toEpochMilli()
                val startMs = startTime.toInstant().toEpochMilli()

                val usageStatsManager =
                    context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val pm = context.packageManager
                val stats = usageStatsManager.queryAndAggregateUsageStats(startMs, endMs)

                val apps = stats.values
                    .filter { stat -> stat.totalTimeInForeground > 0 }
                    .sortedByDescending { stat -> stat.totalTimeInForeground }
                    .take(top)

                val totalMs = stats.values.sumOf { stat -> stat.totalTimeInForeground }

                val payload = buildJsonObject {
                    put("range", if (beginRaw != null || endRaw != null) "custom" else rangePreset)
                    put("start", startTime.withNano(0).toString())
                    put("end", endTime.withNano(0).toString())
                    put("total_ms", totalMs)
                    put("total_minutes", totalMs / 60000)
                    put("apps", buildJsonArray {
                        apps.forEach { stat ->
                            add(buildJsonObject {
                                put("package", stat.packageName)
                                put("app_name", resolveAppName(pm, stat.packageName))
                                put("total_ms", stat.totalTimeInForeground)
                                put("total_minutes", stat.totalTimeInForeground / 60000)
                            })
                        }
                    })
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val calendarQueryTool by lazy {
        Tool(
            name = "calendar_query",
            description = """
                Query calendar events on the user's device within a time range.
                Specify a custom interval with 'begin'/'end', or use the 'range' preset (today/week/month).
                Returns a list of events with title, description, location, start/end times, and calendar info.
                The device timezone is '${ZoneId.systemDefault()}' (UTC offset ${OffsetDateTime.now().offset});
                times without an explicit offset are interpreted in this timezone.
                Requires the 'Calendar' permission; if it is not granted, an error is returned and the
                permission request is triggered automatically.
            """.trimIndent().replace("\\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("begin", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Start time (inclusive). Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                                    "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds. " +
                                    "When provided, 'range' is ignored."
                            )
                        })
                        put("end", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "End time (exclusive), same formats as 'begin'. Defaults to now."
                            )
                        })
                        put("range", buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                buildJsonArray {
                                    add("today")
                                    add("week")
                                    add("month")
                                }
                            )
                            put(
                                "description",
                                "Convenience preset, used only when 'begin' is omitted: today, week, or month. Default today."
                            )
                        })
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional keyword to filter events by title (case-insensitive substring match).")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum number of events to return. Default 20.")
                        })
                    }
                )
            },
            execute = { args ->
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                    val payload = buildJsonObject {
                        put("error", "NO_PERMISSION")
                        put(
                            "message",
                            "Calendar read permission is not granted. Please ask the user to enable " +
                                "the calendar permission in the assistant's local tools settings."
                        )
                    }
                    return@Tool listOf(UIMessagePart.Text(payload.toString()))
                }

                val params = args.jsonObject
                val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                val query = params["query"]?.jsonPrimitive?.contentOrNull

                val now = ZonedDateTime.now()
                val zone = now.zone
                val beginRaw = params["begin"]?.jsonPrimitive?.contentOrNull
                val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
                val rangePreset = params["range"]?.jsonPrimitive?.contentOrNull ?: "today"

                val startTime: ZonedDateTime
                val endTime: ZonedDateTime
                try {
                    startTime = if (beginRaw != null) {
                        parseUsageTime(beginRaw, zone)
                    } else when (rangePreset) {
                        "week" -> now.toLocalDate().atStartOfDay(zone).minusDays(now.dayOfWeek.value.toLong() - 1)
                        "month" -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
                        else -> now.toLocalDate().atStartOfDay(zone)
                    }
                    endTime = if (endRaw != null) {
                        parseUsageTime(endRaw, zone)
                    } else when (rangePreset) {
                        "week" -> startTime.plusDays(7)
                        "month" -> startTime.plusMonths(1)
                        else -> now.toLocalDate().plusDays(1).atStartOfDay(zone)
                    }
                } catch (e: Exception) {
                    val payload = buildJsonObject {
                        put("error", "INVALID_TIME")
                        put("message", e.message ?: "Invalid time format for begin/end.")
                    }
                    return@Tool listOf(UIMessagePart.Text(payload.toString()))
                }

                if (!startTime.isBefore(endTime)) {
                    val payload = buildJsonObject {
                        put("error", "INVALID_RANGE")
                        put("message", "begin must be earlier than end.")
                    }
                    return@Tool listOf(UIMessagePart.Text(payload.toString()))
                }

                val startMs = startTime.toInstant().toEpochMilli()
                val endMs = endTime.toInstant().toEpochMilli()

                val projection = arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.DESCRIPTION,
                    CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                )

                val selection = if (query != null) {
                    "${CalendarContract.Instances.TITLE} LIKE ?"
                } else null
                val selectionArgs = if (query != null) {
                    arrayOf("%$query%")
                } else null

                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                    .appendPath(startMs.toString())
                    .appendPath(endMs.toString())
                    .build()

                val events = buildJsonArray {
                    context.contentResolver.query(
                        uri,
                        projection,
                        selection,
                        selectionArgs,
                        "${CalendarContract.Instances.BEGIN} ASC"
                    )?.use { cursor ->
                        var count = 0
                        while (cursor.moveToNext() && count < limit) {
                            add(buildJsonObject {
                                put("id", cursor.getLong(0))
                                put("title", cursor.getString(1) ?: "")
                                put("description", cursor.getString(2) ?: "")
                                put("location", cursor.getString(3) ?: "")
                                val dtStart = cursor.getLong(4)
                                val dtEnd = cursor.getLong(5)
                                val allDay = cursor.getInt(6) == 1
                                if (allDay) {
                                    put("start", Instant.ofEpochMilli(dtStart).atZone(ZoneOffset.UTC).toLocalDate().toString())
                                    put(
                                        "end",
                                        if (dtEnd > 0) {
                                            Instant.ofEpochMilli(dtEnd).atZone(ZoneOffset.UTC).toLocalDate().toString()
                                        } else ""
                                    )
                                } else {
                                    put("start", Instant.ofEpochMilli(dtStart).atZone(zone).withNano(0).toString())
                                    put(
                                        "end",
                                        if (dtEnd > 0) {
                                            Instant.ofEpochMilli(dtEnd).atZone(zone).withNano(0).toString()
                                        } else ""
                                    )
                                }
                                put("all_day", allDay)
                                put("calendar", cursor.getString(7) ?: "")
                            })
                            count++
                        }
                    }
                }

                val payload = buildJsonObject {
                    put("range_start", startTime.withNano(0).toString())
                    put("range_end", endTime.withNano(0).toString())
                    put("count", events.size)
                    put("events", events)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val calendarCreateTool by lazy {
        Tool(
            name = "calendar_create",
            description = """
                Create a new calendar event on the user's device.
                Requires title and start time at minimum. End time defaults to 1 hour after start.
                The device timezone is '${ZoneId.systemDefault()}' (UTC offset ${OffsetDateTime.now().offset});
                times without an explicit offset are interpreted in this timezone.
                Requires the 'Calendar' permission; if it is not granted, an error is returned and the
                permission request is triggered automatically.
            """.trimIndent().replace("\\n", " "),
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("title", buildJsonObject {
                            put("type", "string")
                            put("description", "Event title.")
                        })
                        put("description", buildJsonObject {
                            put("type", "string")
                            put("description", "Event description or notes.")
                        })
                        put("location", buildJsonObject {
                            put("type", "string")
                            put("description", "Event location.")
                        })
                        put("start", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Start time. Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                                    "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds."
                            )
                        })
                        put("end", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "End time, same formats as 'start'. Defaults to 1 hour after start."
                            )
                        })
                        put("all_day", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Whether this is an all-day event. Default false.")
                        })
                    },
                    required = listOf("title", "start")
                )
            },
            execute = { args ->
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                    val payload = buildJsonObject {
                        put("error", "NO_PERMISSION")
                        put(
                            "message",
                            "Calendar write permission is not granted. Please ask the user to enable " +
                                "the calendar permission in the assistant's local tools settings."
                        )
                    }
                    return@Tool listOf(UIMessagePart.Text(payload.toString()))
                }

                val params = args.jsonObject
                val title = params["title"]?.jsonPrimitive?.contentOrNull
                val startRaw = params["start"]?.jsonPrimitive?.contentOrNull
                val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
                val allDay = params["all_day"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

                if (title.isNullOrBlank() || startRaw.isNullOrBlank()) {
                    val payload = buildJsonObject {
                        put("error", "MISSING_REQUIRED")
                        put("message", "Both 'title' and 'start' are required.")
                    }
                    return@Tool listOf(UIMessagePart.Text(payload.toString()))
                }

                val zone = ZoneId.systemDefault()
                val startTime: ZonedDateTime
                val endTime: ZonedDateTime
                try {
                    startTime = parseUsageTime(startRaw, zone)
                    endTime = if (endRaw != null) {
                        parseUsageTime(endRaw, zone)
                    } else if (allDay) {
                        startTime.toLocalDate().plusDays(1).atStartOfDay(zone)
                    } else {
                        startTime.plusHours(1)
                    }
                } catch (e: Exception) {
                    val payload = buildJsonObject {
                        put("error", "INVALID_TIME")
                        put("message", e.message ?: "Invalid time format.")
                    }
                    return@Tool listOf(UIMessagePart.Text(payload.toString()))
                }

                if (!startTime.isBefore(endTime)) {
                    val payload = buildJsonObject {
                        put("error", "INVALID_RANGE")
                        put("message", "end must be later than start.")
                    }
                    return@Tool listOf(UIMessagePart.Text(payload.toString()))
                }

                val description = params["description"]?.jsonPrimitive?.contentOrNull ?: ""
                val location = params["location"]?.jsonPrimitive?.contentOrNull ?: ""

                val eventStartMillis: Long
                val eventEndMillis: Long
                val eventTimeZone: String
                if (allDay) {
                    val startDate = startTime.toLocalDate()
                    val endDate = endTime.toLocalDate()
                    if (!startDate.isBefore(endDate)) {
                        val payload = buildJsonObject {
                            put("error", "INVALID_RANGE")
                            put("message", "all-day event end date must be later than start date.")
                        }
                        return@Tool listOf(UIMessagePart.Text(payload.toString()))
                    }
                    eventStartMillis = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    eventEndMillis = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    eventTimeZone = "UTC"
                } else {
                    eventStartMillis = startTime.toInstant().toEpochMilli()
                    eventEndMillis = endTime.toInstant().toEpochMilli()
                    eventTimeZone = zone.id
                }

                // Find a writable calendar
                var calendarId: Long? = null
                val calProjection = arrayOf(CalendarContract.Calendars._ID)
                val writableSelection =
                    "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1"
                val writableArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
                // Try primary calendar first
                context.contentResolver.query(
                    CalendarContract.Calendars.CONTENT_URI,
                    calProjection,
                    "$writableSelection AND ${CalendarContract.Calendars.IS_PRIMARY} = 1",
                    writableArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) calendarId = cursor.getLong(0)
                }
                if (calendarId == null) {
                    context.contentResolver.query(
                        CalendarContract.Calendars.CONTENT_URI,
                        calProjection,
                        writableSelection,
                        writableArgs,
                        "${CalendarContract.Calendars.VISIBLE} DESC"
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) calendarId = cursor.getLong(0)
                    }
                }

                if (calendarId == null) {
                    val payload = buildJsonObject {
                        put("error", "NO_CALENDAR")
                        put("message", "No calendar account found on this device. Please add a calendar account first.")
                    }
                    return@Tool listOf(UIMessagePart.Text(payload.toString()))
                }

                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DESCRIPTION, description)
                    put(CalendarContract.Events.EVENT_LOCATION, location)
                    put(CalendarContract.Events.DTSTART, eventStartMillis)
                    put(CalendarContract.Events.DTEND, eventEndMillis)
                    put(CalendarContract.Events.EVENT_TIMEZONE, eventTimeZone)
                    if (allDay) {
                        put(CalendarContract.Events.ALL_DAY, 1)
                    }
                }

                val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                if (uri == null) {
                    val payload = buildJsonObject {
                        put("error", "INSERT_FAILED")
                        put("message", "Failed to insert calendar event.")
                    }
                    return@Tool listOf(UIMessagePart.Text(payload.toString()))
                }

                val eventId = ContentUris.parseId(uri)
                val payload = buildJsonObject {
                    put("success", true)
                    put("event_id", eventId)
                    put("title", title)
                    put("start", startTime.withNano(0).toString())
                    put("end", endTime.withNano(0).toString())
                    put("all_day", allDay)
                    put("location", location)
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
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        return tools
    }
}

/** Resolve a package name to its user-friendly app label. */
private fun resolveAppName(pm: PackageManager, packageName: String): String {
    return runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}

/**
 * Parse begin/end time parameters, trying: epoch ms → offset datetime → Instant →
 * local datetime → local date (start of day). Throws on all failures.
 */
private fun parseUsageTime(raw: String, zone: ZoneId): ZonedDateTime {
    val text = raw.trim()
    text.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
    runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
    runCatching { return Instant.parse(text).atZone(zone) }
    runCatching { return LocalDateTime.parse(text).atZone(zone) }
    runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
    error("Invalid time format: '$raw'. Use ISO-8601 date/date-time or epoch milliseconds.")
}



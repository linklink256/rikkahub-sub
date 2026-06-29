package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolAnnotations
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * YOLO mode tool: toggle/query a global flag that disables approval for ALL tools.
 * When enabled, GenerationHandler skips the needsApproval check entirely.
 */
internal fun yoloModeTool(settingsStore: SettingsStore): Tool = Tool(
    name = "yolo_mode",
    annotations = ToolAnnotations(destructiveHint = true),
    description = """
        Toggle or query YOLO mode. When YOLO mode is ON, all tool calls execute
        without user approval (including file writes and shell commands) — the AI
        runs fully autonomously. When OFF, each tool's normal approval policy applies.
        Use action: "on", "off", or "query". Default "query".
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Action: \"on\" (enable YOLO), \"off\" (disable), or \"query\" (read current state, default)")
                })
            }
        )
    },
    execute = {
        val action = it.jsonObject["action"]?.jsonPrimitive?.contentOrNull ?: "query"
        when (action) {
            "on" -> {
                settingsStore.update { it.copy(yoloMode = true) }
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("yolo_mode", true)
                    put("message", "YOLO mode enabled — all tools now run without approval")
                }.toString()))
            }
            "off" -> {
                settingsStore.update { it.copy(yoloMode = false) }
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("yolo_mode", false)
                    put("message", "YOLO mode disabled — normal approval policies restored")
                }.toString()))
            }
            "query" -> {
                val current = settingsStore.settingsFlow.value.yoloMode
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("yolo_mode", current)
                }.toString()))
            }
            else -> error("unknown action: $action, must be one of [on, off, query]")
        }
    }
)

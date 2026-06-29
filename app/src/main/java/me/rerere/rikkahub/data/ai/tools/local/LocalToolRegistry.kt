package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus

/**
 * Registry mapping each [LocalToolOption] to the factory that builds its tool(s).
 *
 * Replaces the previous 10-branch if/else in LocalTools.getTools().
 * To add a new local tool: create a factory function in its own file and
 * register it here. No other changes needed.
 */
internal val LocalToolRegistry: Map<LocalToolOption, (Context, AppEventBus, SettingsStore) -> List<Tool>> = mapOf(
    LocalToolOption.JavascriptEngine to { _, _, _ -> listOf(evalJavaScriptTool()) },
    LocalToolOption.TimeInfo to { _, _, _ -> listOf(getTimeInfoTool()) },
    LocalToolOption.Clipboard to { context, _, _ -> listOf(clipboardTool(context)) },
    LocalToolOption.Tts to { _, eventBus, _ -> listOf(ttsTool(eventBus)) },
    LocalToolOption.AskUser to { _, _, _ -> listOf(askUserTool()) },
    LocalToolOption.Fetch to { _, _, _ -> listOf(fetchTool()) },
    LocalToolOption.Logs to { _, _, _ -> listOf(logsTool()) },
    LocalToolOption.ScreenTime to { context, eventBus, _ -> listOf(screenTimeTool(context, eventBus)) },
    LocalToolOption.Calendar to { context, _, _ -> calendarTools(context) },
    LocalToolOption.YoloMode to { _, _, settingsStore -> listOf(yoloModeTool(settingsStore)) },
    LocalToolOption.NetworkProxy to { _, _, settingsStore -> listOf(networkProxyTool(settingsStore)) },
    // AskBtw intentionally absent — it's subagent-only, no main-agent tool.
)

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.local.LocalToolRegistry
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus

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

    @Serializable
    @SerialName("yolo_mode")
    data object YoloMode : LocalToolOption()

    @Serializable
    @SerialName("network_proxy")
    data object NetworkProxy : LocalToolOption()
}

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val settingsStore: SettingsStore,
) {
    fun getTools(options: List<LocalToolOption>): List<Tool> =
        options.flatMap { option ->
            LocalToolRegistry[option]?.invoke(context, eventBus, settingsStore) ?: emptyList()
        }
}

package me.rerere.asr

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.rerere.asr.providers.DashScopeASRController
import me.rerere.asr.providers.MiMoASRController
import me.rerere.asr.providers.OpenAIRealtimeASRController
import me.rerere.asr.providers.StepASRController
import me.rerere.asr.providers.VolcengineASRController
import okhttp3.OkHttpClient

/**
 * ASR 控制器包装类（对应 TtsController）。
 *
 * 长期存在，内部管理 provider-specific 的 [ASRController] 实现。
 * [setProvider] 仅在 provider 真正变化时才销毁旧控制器并创建新控制器，
 * 避免每次 Compose 重组都重复创建/销毁并产生日志噪音。
 */
class AsrController(
    private val context: Context,
    private val httpClient: OkHttpClient
) {
    private var currentProvider: ASRProviderSetting? = null
    private var innerController: ASRController? = null
    private val idleState = MutableStateFlow(ASRState())

    val state: StateFlow<ASRState>
        get() = innerController?.state ?: idleState

    fun setProvider(provider: ASRProviderSetting?) {
        if (provider == currentProvider) return
        innerController?.dispose()
        currentProvider = provider
        innerController = if (provider != null) createController(provider) else null
        if (innerController == null) {
            idleState.value = ASRState()
        }
    }

    fun start(
        onTranscriptChange: (String) -> Unit,
        onTranscriptComplete: ((String) -> Unit)? = null
    ) {
        innerController?.start(onTranscriptChange, onTranscriptComplete)
    }

    fun stop() {
        innerController?.stop()
    }

    fun dispose() {
        innerController?.dispose()
        innerController = null
        currentProvider = null
    }

    private fun createController(provider: ASRProviderSetting): ASRController? {
        return when (provider) {
            is ASRProviderSetting.OpenAIRealtime -> {
                if (provider.apiKey.isBlank()) return null
                OpenAIRealtimeASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.DashScope -> {
                if (provider.apiKey.isBlank()) return null
                // Auto-migrate old URL: /api-ws/v1/inference → /api-ws/v1/realtime
                val fixedProvider = if (provider.websocketUrl.contains("/api-ws/v1/inference")) {
                    provider.copy(
                        websocketUrl = provider.websocketUrl.replace(
                            "/api-ws/v1/inference",
                            "/api-ws/v1/realtime"
                        )
                    )
                } else {
                    provider
                }
                DashScopeASRController(context, httpClient, fixedProvider)
            }

            is ASRProviderSetting.Volcengine -> {
                if (provider.apiKey.isBlank()) return null
                VolcengineASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.MiMo -> {
                if (provider.apiKey.isBlank()) return null
                MiMoASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.Step -> {
                if (provider.apiKey.isBlank()) return null
                StepASRController(context, httpClient, provider)
            }
        }
    }
}

package me.rerere.rikkahub.ui.hooks

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.providers.DashScopeASRController
import me.rerere.asr.providers.MiMoASRController
import me.rerere.asr.providers.OpenAIRealtimeASRController
import me.rerere.asr.providers.StepASRController
import me.rerere.asr.providers.SystemASRController
import me.rerere.asr.providers.VolcengineASRController
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

@Composable
fun rememberCustomAsrState(): CustomAsrState {
    val context = LocalContext.current
    val settingsStore = koinInject<SettingsStore>()
    val httpClient = koinInject<OkHttpClient>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    val asrState = remember {
        CustomAsrStateImpl(context.applicationContext, httpClient)
    }

    DisposableEffect(settings.selectedASRProviderId, settings.asrProviders) {
        asrState.updateProvider(settings.getSelectedASRProvider())
        onDispose { }
    }

    DisposableEffect(asrState) {
        onDispose {
            asrState.cleanup()
        }
    }

    return asrState
}

interface CustomAsrState {
    val state: StateFlow<ASRState>
    fun start(onTranscriptChange: (String) -> Unit, onTranscriptComplete: ((String) -> Unit)? = null)
    fun stop()
    fun cleanup()
}

private class CustomAsrStateImpl(
    private val context: Context,
    private val httpClient: OkHttpClient
) : CustomAsrState {
    private var controller: ASRController? = null
    private val idleState = MutableStateFlow(ASRState())

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAcceptsDelayedFocusGain(false)
        .build()

    override val state: StateFlow<ASRState>
        get() = controller?.state ?: idleState

    fun updateProvider(provider: ASRProviderSetting?) {
        controller?.dispose()
        if (provider == null) {
            Logging.log("ASRHook", "Provider is null, no controller created")
            controller = null
        } else {
            controller = createController(provider)
            Logging.log("ASRHook", "Provider updated: ${provider::class.simpleName}")
        }
        if (controller == null) {
            idleState.value = ASRState()
        }
    }

    override fun start(onTranscriptChange: (String) -> Unit, onTranscriptComplete: ((String) -> Unit)?) {
        Logging.log("ASRHook", "start() called, controller exists: ${controller != null}")
        val result = audioManager.requestAudioFocus(audioFocusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Logging.log("ASRHook", "Audio focus granted, starting controller")
            controller?.start(onTranscriptChange, onTranscriptComplete)
        } else {
            Logging.log("ASRHook", "Audio focus DENIED")
        }
    }

    override fun stop() {
        Logging.log("ASRHook", "stop() called")
        controller?.stop()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    override fun cleanup() {
        controller?.dispose()
        controller = null
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    private fun createController(provider: ASRProviderSetting): ASRController? {
        return when (provider) {
            is ASRProviderSetting.OpenAIRealtime -> {
                if (provider.apiKey.isBlank()) {
                    Logging.log("ASRHook", "OpenAIRealtime apiKey is blank, returning null controller")
                    return null
                }
                Logging.log("ASRHook", "Creating OpenAIRealtimeASRController")
                OpenAIRealtimeASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.DashScope -> {
                if (provider.apiKey.isBlank()) {
                    Logging.log("ASRHook", "DashScope apiKey is blank, returning null controller")
                    return null
                }
                // Auto-migrate old URL: /api-ws/v1/inference → /api-ws/v1/realtime
                val fixedProvider = if (provider.websocketUrl.contains("/api-ws/v1/inference")) {
                    Logging.log("ASRHook", "DashScope URL migration: ${provider.websocketUrl} -> using /api-ws/v1/realtime")
                    provider.copy(websocketUrl = provider.websocketUrl.replace("/api-ws/v1/inference", "/api-ws/v1/realtime"))
                } else {
                    provider
                }
                Logging.log("ASRHook", "Creating DashScopeASRController")
                DashScopeASRController(context, httpClient, fixedProvider)
            }

            is ASRProviderSetting.Volcengine -> {
                if (provider.apiKey.isBlank()) {
                    Logging.log("ASRHook", "Volcengine apiKey is blank, returning null controller")
                    return null
                }
                Logging.log("ASRHook", "Creating VolcengineASRController")
                VolcengineASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.MiMo -> {
                if (provider.apiKey.isBlank()) {
                    Logging.log("ASRHook", "MiMo apiKey is blank, returning null controller")
                    return null
                }
                Logging.log("ASRHook", "Creating MiMoASRController")
                MiMoASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.Step -> {
                if (provider.apiKey.isBlank()) {
                    Logging.log("ASRHook", "Step apiKey is blank, returning null controller")
                    return null
                }
                Logging.log("ASRHook", "Creating StepASRController")
                StepASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.SystemASR -> {
                Logging.log("ASRHook", "Creating SystemASRController")
                SystemASRController(context, provider)
            }
        }
    }
}

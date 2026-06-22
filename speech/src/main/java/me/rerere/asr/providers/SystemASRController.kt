package me.rerere.asr.providers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.appendAmplitude
import java.util.Collections

private const val TAG = "SystemASR"

/**
 * 系统 ASR Controller, 基于 Android Framework 的 [SpeechRecognizer]。
 *
 * 与其他 Controller 不同, 本 Controller 不依赖任何云端 API, 不需要 apiKey,
 * 直接调用 Android 系统内置的语音识别引擎。支持 [ASRProviderSetting.SystemASR.preferOffline]
 * 时优先使用设备端离线模型 (Android 10+)。
 *
 * SpeechRecognizer 是一次性识别接口 (说一段话后自动停止), 本 Controller 通过在
 * [RecognitionListener.onResults] 和 [RecognitionListener.onError] 中自动重启来实现
 * 连续识别效果。每次识别的最终结果会累积到 completedTranscripts, partial 结果实时
 * 拼接到末尾, 通过 onTranscriptChange 回调上报完整 transcript。
 *
 * 注意: [SpeechRecognizer.createSpeechRecognizer] 必须在主线程调用, 本 Controller 的
 * scope 使用 [Dispatchers.Main.immediate] 保证这一点。
 */
class SystemASRController(
    private val context: Context,
    private val provider: ASRProviderSetting.SystemASR
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(
        ASRState(isAvailable = isSpeechRecognitionAvailable(context))
    )
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private var isListening = false
    private var clientErrorRetryCount = 0

    private val completedTranscripts = Collections.synchronizedList(mutableListOf<String>())
    private var currentPartial = ""

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording) return

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setError("Microphone permission is required")
            return
        }

        if (!isSpeechRecognitionAvailable(context)) {
            // 设备可能没有 Google 服务的 SpeechRecognizer, 但可能有第三方语音引擎。
            // 不直接拒绝, 而是尝试创建并启动; 若 createSpeechRecognizer 返回 null
            // 或 startListening 抛异常, 再报错。
            Log.w(TAG, "isRecognitionAvailable=false, but trying to start anyway")
        }

        this.onTranscriptChange = onTranscriptChange
        synchronized(completedTranscripts) {
            completedTranscripts.clear()
        }
        currentPartial = ""
        clientErrorRetryCount = 0
        isListening = true

        _state.update {
            ASRState(
                status = ASRStatus.Listening,
                isAvailable = true
            )
        }

        startListening()
    }

    /**
     * 创建新的 SpeechRecognizer 实例并开始监听。
     * 每次调用都会先销毁旧实例, 确保状态干净。
     * 必须在主线程调用。
     */
    private fun startListening() {
        speechRecognizer?.destroy()
        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer", e)
            setError("无法创建语音识别器: ${e.message}")
            isListening = false
            _state.update { it.copy(status = ASRStatus.Error) }
            return
        }
        if (recognizer == null) {
            setError("设备不支持语音识别")
            isListening = false
            _state.update { it.copy(status = ASRStatus.Error) }
            return
        }
        speechRecognizer = recognizer

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, provider.preferOffline)
            if (provider.language.isNotBlank()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, provider.language)
            }
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsDb: Float) {
                // rmsDb 通常在 -2 ~ 10 范围, 映射到 0..1
                val normalized = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
                _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(normalized)) }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {}

            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                val errorMsg = mapError(error)
                Log.w(TAG, "Recognition error: $error ($errorMsg)")

                if (!isListening) return

                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        setError(errorMsg)
                        isListening = false
                    }
                    SpeechRecognizer.ERROR_CLIENT -> {
                        // ERROR_CLIENT 通常是临时性的 (音频焦点冲突、音频系统短暂繁忙),
                        // 重试几次而非直接放弃。超过上限则判定为设备不支持。
                        clientErrorRetryCount++
                        if (clientErrorRetryCount > 3) {
                            setError("系统语音识别不可用 (Client error), 请尝试在设置中配置其他 ASR provider")
                            isListening = false
                        } else {
                            scope.launch {
                                if (isListening && isActive) {
                                    delay(500L * clientErrorRetryCount)
                                    if (isListening) startListening()
                                }
                            }
                        }
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // 繁忙时等一下再重启, 避免紧密循环
                        scope.launch {
                            if (isListening && isActive) {
                                delay(500)
                                if (isListening) startListening()
                            }
                        }
                    }
                    else -> {
                        // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT 等是正常的语音结束事件,
                        // 自动重启以实现连续识别
                        scope.launch {
                            if (isListening && isActive) {
                                delay(100)
                                if (isListening) startListening()
                            }
                        }
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (partial.isNotEmpty()) {
                    currentPartial = partial
                    publishTranscript()
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotEmpty()) {
                    synchronized(completedTranscripts) {
                        completedTranscripts.add(text)
                    }
                }
                currentPartial = ""
                publishTranscript()

                // 仍然在监听则自动重启, 实现连续识别
                if (isListening) {
                    scope.launch {
                        if (isListening && isActive) {
                            delay(100)
                            if (isListening) startListening()
                        }
                    }
                }
            }
        })

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            setError("启动语音识别失败: ${e.message}")
            isListening = false
            _state.update { it.copy(status = ASRStatus.Error) }
        }
    }

    override fun stop() {
        isListening = false
        _state.update { it.copy(status = ASRStatus.Stopping) }
        speechRecognizer?.stopListening()
        // 短暂等待最终结果回调, 然后切回 Idle
        scope.launch {
            delay(300)
            _state.update { it.copy(status = ASRStatus.Idle) }
        }
    }

    override fun dispose() {
        isListening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        scope.cancel()
    }

    private fun publishTranscript() {
        val transcript = synchronized(completedTranscripts) {
            val completed = completedTranscripts.filter { it.isNotBlank() }
            if (currentPartial.isNotBlank()) {
                (completed + currentPartial).joinToString(" ")
            } else {
                completed.joinToString(" ")
            }
        }
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        scope.launch { onTranscriptChange?.invoke(transcript) }
    }

    private fun setError(message: String) {
        _state.update {
            it.copy(
                status = ASRStatus.Error,
                errorMessage = message
            )
        }
    }

    companion object {
        fun isSpeechRecognitionAvailable(context: Context): Boolean {
            return try {
                SpeechRecognizer.isRecognitionAvailable(context)
            } catch (e: Exception) {
                false
            }
        }

        private fun mapError(error: Int): String {
            return when (error) {
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                SpeechRecognizer.ERROR_NO_MATCH -> "No recognition match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                else -> "Unknown error ($error)"
            }
        }
    }
}

package me.rerere.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import me.rerere.asr.calculateRmsAmplitude
import me.rerere.common.android.Logging
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.uuid.Uuid

private const val TAG = "VolcengineASR"
private const val MAX_WEBSOCKET_QUEUE_BYTES = 100_000L

class VolcengineASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.Volcengine
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private var onTranscriptComplete: ((String) -> Unit)? = null
    private var lastText = ""

    // stop() 幂等保护: 防止重复调用导致 close 触发 onFailure 报错
    @Volatile
    private var isStopping = false

    // 客户端 VAD 自动分句 (火山引擎 SAUC bigmodel 无 server-side VAD)
    @Volatile
    private var autoStoppedByVad = false

    override fun start(
        onTranscriptChange: (String) -> Unit,
        onTranscriptComplete: ((String) -> Unit)?
    ) {
        if (state.value.isRecording) return
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setError("Microphone permission is required")
            return
        }

        this.onTranscriptChange = onTranscriptChange
        this.onTranscriptComplete = onTranscriptComplete
        lastText = ""
        isStopping = false
        autoStoppedByVad = false
        _state.update {
            ASRState(
                status = ASRStatus.Connecting,
                isAvailable = true
            )
        }

        val request = Request.Builder()
            .url(provider.websocketUrl)
            .addHeader("X-Api-Key", provider.apiKey)
            .addHeader("X-Api-Resource-Id", provider.resourceId)
            .addHeader("X-Api-Request-Id", Uuid.random().toString())
            .addHeader("X-Api-Sequence", "-1")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val payload = buildFullClientRequestPayload()
                val compressed = gzipCompress(payload)
                val frame = buildFrame(
                    messageType = MSG_FULL_CLIENT_REQUEST,
                    flags = 0x00,
                    serialization = SER_JSON,
                    compression = COMP_GZIP,
                    payload = compressed
                )
                webSocket.send(frame.toByteString())
                _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
                startRecorder(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinaryResponse(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Volcengine ASR websocket failed", t)
                releaseRecorder()
                // 主动关闭过程中触发的 onFailure 不算错误 (stop 导致的异步关闭)
                if (isStopping) {
                    Logging.log(TAG, "onFailure ignored during stopping: ${t.message}")
                    return
                }
                setError(t.message ?: "ASR websocket failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                releaseRecorder()
                _state.update { it.copy(status = ASRStatus.Idle, errorMessage = null) }
            }
        })
    }

    override fun stop() {
        // 幂等保护: 已在关闭中则跳过, 避免重复 close 触发 onFailure
        if (isStopping) {
            Logging.log(TAG, "stop() called but already stopping, ignored")
            return
        }
        isStopping = true
        val triggeredByVad = autoStoppedByVad
        Logging.log(TAG, "stop() called, triggeredByVad=$triggeredByVad")
        recorderJob?.cancel()
        releaseRecorder()
        val socket = webSocket
        if (socket != null) {
            _state.update { it.copy(status = ASRStatus.Stopping) }
            val lastFrame = buildFrame(
                messageType = MSG_AUDIO_ONLY,
                flags = FLAG_LAST_PACKET,
                serialization = SER_NONE,
                compression = COMP_NONE,
                payload = ByteArray(0)
            )
            socket.send(lastFrame.toByteString())
            scope.launch {
                delay(1000)
                socket.close(1000, "stop")
                if (webSocket === socket) {
                    webSocket = null
                    _state.update { it.copy(status = ASRStatus.Idle) }
                }
            }
        } else {
            _state.update { it.copy(status = ASRStatus.Idle) }
        }
    }

    override fun dispose() {
        stop()
        scope.cancel()
    }

    private fun buildFullClientRequestPayload(): ByteArray {
        val audio = JSONObject()
            .put("format", "pcm")
            .put("rate", SAMPLE_RATE)
            .put("bits", 16)
            .put("channel", 1)
        if (provider.language.isNotBlank()) {
            audio.put("language", provider.language)
        }

        val json = JSONObject()
            .put("user", JSONObject().put("uid", "rikkahub"))
            .put("audio", audio)
            .put(
                "request", JSONObject()
                    .put("model_name", "bigmodel")
                    .put("enable_itn", true)
                    .put("enable_punc", true)
                    .put("show_utterances", true)
                    .put("result_type", "full")
            )
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun handleBinaryResponse(data: ByteArray) {
        if (data.size < 4) return

        val byte1 = data[1].toInt() and 0xFF
        val byte2 = data[2].toInt() and 0xFF
        val messageType = (byte1 shr 4) and 0x0F
        val messageFlags = byte1 and 0x0F
        val compression = byte2 and 0x0F

        var offset = 4

        when (messageType) {
            0x09 -> {
                val hasSequence = (messageFlags and 0x01) != 0
                if (hasSequence) offset += 4

                if (offset + 4 > data.size) return
                val payloadSize = ByteBuffer.wrap(data, offset, 4)
                    .order(ByteOrder.BIG_ENDIAN).int
                offset += 4

                if (payloadSize <= 0 || offset + payloadSize > data.size) return

                var payload = data.copyOfRange(offset, offset + payloadSize)
                if (compression == COMP_GZIP) {
                    payload = runCatching { gzipDecompress(payload) }.getOrElse {
                        Log.w(TAG, "Gzip decompression failed", it)
                        return
                    }
                }

                val json = runCatching {
                    JSONObject(String(payload, Charsets.UTF_8))
                }.getOrElse {
                    Log.w(TAG, "Failed to parse response JSON", it)
                    return
                }

                val result = json.optJSONObject("result")
                val text = result?.optString("text", "") ?: ""
                if (text.isNotEmpty() && text != lastText) {
                    lastText = text
                    _state.update { it.copy(transcript = text, errorMessage = null) }
                    scope.launch { onTranscriptChange?.invoke(text) }

                    // 火山引擎 SAUC bigmodel 的完成信号有两个来源:
                    // 1) 服务器 VAD 分句: utterances[].definite=true (仅 bigmodel_async 模式支持)
                    // 2) 客户端触发的最终响应: 客户端发送 FLAG_LAST_PACKET 负包后,
                    //    服务器返回的最后一包响应, messageFlags=0b0011 (sequence 负数)
                    // 普通模式下 definite 不会自动变 true, 必须靠客户端 VAD 检测用户说完话
                    // 后主动调用 stop() 发负包, 让服务器返回最终结果触发 onTranscriptComplete。
                    val utterances = result?.optJSONArray("utterances")
                    val definiteFromUtterance = if (utterances != null && utterances.length() > 0) {
                        val lastUtterance = utterances.optJSONObject(utterances.length() - 1)
                        lastUtterance?.optBoolean("definite", false) ?: false
                    } else {
                        false
                    }
                    // message type specific flags = 0b0011 表示最后一包结果 (客户端 stop() 触发)
                    val isLastPacket = (messageFlags and 0x03) == 0x03
                    Logging.log(
                        TAG,
                        "Response: definite=$definiteFromUtterance, isLastPacket=$isLastPacket, flags=0x${messageFlags.toString(16)}, text=${text.take(50)}"
                    )

                    if (definiteFromUtterance || isLastPacket) {
                        Logging.log(TAG, "Transcript complete (definite=$definiteFromUtterance, lastPacket=$isLastPacket): $text")
                        scope.launch { onTranscriptComplete?.invoke(text) }
                    }
                }
            }

            0x0F -> {
                if (offset + 4 > data.size) return
                offset += 4 // skip error code

                if (offset + 4 > data.size) return
                val msgSize = ByteBuffer.wrap(data, offset, 4)
                    .order(ByteOrder.BIG_ENDIAN).int
                offset += 4

                val errorMsg = if (msgSize > 0 && offset + msgSize <= data.size) {
                    String(data, offset, msgSize, Charsets.UTF_8)
                } else {
                    "Volcengine ASR error"
                }
                Log.e(TAG, "Volcengine ASR error: $errorMsg")
                setError(errorMsg)
            }

            else -> Log.v(TAG, "Ignored message type: $messageType")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder(socket: WebSocket) {
        recorderJob?.cancel()
        recorderJob = scope.launch(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val chunkSize = (SAMPLE_RATE * 2 * 200 / 1000).coerceAtLeast(minBufferSize)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                chunkSize * 2
            )
            audioRecord = recorder

            // 客户端 VAD: 检测用户说完话后自动 stop (发负包触发服务器返回最终结果)
            // 火山引擎 SAUC bigmodel 默认无 server-side VAD, definite=true 只在分句时返回;
            // 在普通模式不会自动分句, 必须由客户端告知服务器"我说完了" → 发 FLAG_LAST_PACKET。
            var speechDetected = false
            var silenceChunks = 0
            // chunkSize 每 chunk ~200ms → 4 个静音 chunk ≈ 800ms 静音判定说完话
            val silenceChunkLimit = 4
            // 归一化 RMS 阈值 (0.0~1.0, 来源于 calculateRmsAmplitude: -60..0 dB 映射)
            // 0.15 ≈ -39dB; VOICE_COMMUNICATION 已启用硬件 AEC, 环境噪声经 AEC 后通常 < 0.1
            val speechRmsThreshold = 0.15f

            try {
                recorder.startRecording()
                Logging.log(TAG, "AudioRecord started, chunkMs=200, vadThreshold=$speechRmsThreshold, silenceChunkLimit=$silenceChunkLimit")
                val buffer = ByteArray(chunkSize)
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val amplitude = calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }

                        // 客户端 VAD 状态机: 说话 → 静音 800ms → 自动 stop
                        if (amplitude >= speechRmsThreshold) {
                            if (!speechDetected) {
                                Logging.log(TAG, "VAD: speech start detected, amp=${"%.3f".format(amplitude)}")
                            }
                            speechDetected = true
                            silenceChunks = 0
                        } else if (speechDetected) {
                            silenceChunks++
                            if (silenceChunks >= silenceChunkLimit && !autoStoppedByVad) {
                                Logging.log(TAG, "VAD: ${silenceChunks * 200}ms silence after speech, auto-stop ASR")
                                autoStoppedByVad = true
                                // 在主 scope 调 stop() 发最后一包并等待服务器最终响应
                                scope.launch(Dispatchers.Main.immediate) { stop() }
                                break
                            }
                        }

                        if (socket.queueSize() < MAX_WEBSOCKET_QUEUE_BYTES) {
                            val frame = buildFrame(
                                messageType = MSG_AUDIO_ONLY,
                                flags = 0x00,
                                serialization = SER_NONE,
                                compression = COMP_NONE,
                                payload = buffer.copyOfRange(0, read)
                            )
                            socket.send(frame.toByteString())
                        } else {
                            Log.w(TAG, "WebSocket queue full, dropping audio frame")
                        }
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio recording failed", e)
                if (!isStopping) {
                    setError(e.message ?: "Audio recording failed")
                }
            } finally {
                releaseRecorder()
            }
        }
    }

    private fun setError(message: String) {
        _state.update { it.copy(status = ASRStatus.Error, errorMessage = message) }
    }

    private fun releaseRecorder() {
        recorderJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val MSG_FULL_CLIENT_REQUEST = 0x01
        private const val MSG_AUDIO_ONLY = 0x02
        private const val SER_NONE = 0x00
        private const val SER_JSON = 0x01
        private const val COMP_NONE = 0x00
        private const val COMP_GZIP = 0x01
        private const val FLAG_LAST_PACKET = 0x02

        private fun buildFrame(
            messageType: Int,
            flags: Int,
            serialization: Int,
            compression: Int,
            payload: ByteArray
        ): ByteArray {
            val header = byteArrayOf(
                0x11.toByte(),
                ((messageType shl 4) or (flags and 0x0F)).toByte(),
                ((serialization shl 4) or (compression and 0x0F)).toByte(),
                0x00
            )
            val size = ByteBuffer.allocate(4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(payload.size)
                .array()
            return header + size + payload
        }

        private fun gzipCompress(data: ByteArray): ByteArray {
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(data) }
            return bos.toByteArray()
        }

        private fun gzipDecompress(data: ByteArray): ByteArray {
            return GZIPInputStream(data.inputStream()).use { it.readBytes() }
        }
    }
}

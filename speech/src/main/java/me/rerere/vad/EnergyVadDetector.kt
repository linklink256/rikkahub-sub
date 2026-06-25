package me.rerere.vad

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.common.android.Logging
import kotlin.math.sqrt

private const val TAG = "EnergyVAD"

/**
 * 轻量能量 VAD（语音活动检测）。
 *
 * 在 TTS 播报期间用 [AudioRecord] 录音并计算每帧 RMS 能量，
 * 当连续多帧能量超过阈值时判定用户正在说话，触发 [onSpeechDetected]。
 *
 * 这是一个 one-shot 检测器：触发一次后自动停止，
 * 由调用方决定是否重新 [start]。
 *
 * 使用 [MediaRecorder.AudioSource.VOICE_COMMUNICATION] 启用硬件 AEC 回声消除，
 * 避免 TTS 扬声器声音被误判为用户说话。
 */
class EnergyVadDetector(
    private val context: Context,
    private val sampleRate: Int = 16000,
    private val frameDurationMs: Int = 32,
    private val energyThreshold: Double = 6000.0,
    private val speechDurationMs: Int = 640,
    private val onSpeechDetected: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 每帧样本数：sampleRate * frameDurationMs / 1000（16kHz / 32ms = 512 samples） */
    private val frameSizeInSamples: Int = sampleRate * frameDurationMs / 1000

    /** 判定说话所需连续超过阈值的帧数：speechDurationMs / frameDurationMs */
    private val requiredSpeechFrames: Int = speechDurationMs / frameDurationMs

    private var detectorJob: Job? = null
    private var audioRecord: AudioRecord? = null

    // Note: deliberately named `isActive` per spec; do NOT import
    // kotlinx.coroutines.isActive so this member is unambiguous inside the loop.
    @Volatile
    private var isActive = false

    /**
     * 当前录音链路是否具备硬件 AEC（回声消除）。
     *
     * 仅当 [MediaRecorder.AudioSource.VOICE_COMMUNICATION] 成功初始化时为 true；
     * 若该源初始化失败、回退到 [MediaRecorder.AudioSource.MIC] 则为 false（MIC 无 AEC）。
     * 上层可据此决定是否启用 barge-in——无 AEC 时 TTS 扬声器声必然被收录并误判为
     * 用户说话，应禁用自动打断（用户仍可手动点击打断按钮）。
     *
     * 在 [start] 完成录音源选择之前为 false（安全默认：未确认 AEC 时不信任）。
     * 以 [StateFlow] 暴露，上层可 collect 状态变化或在回调中通过 [hasAec].value 线程安全读取。
     */
    private val _hasAec = MutableStateFlow(false)
    val hasAec: StateFlow<Boolean> = _hasAec.asStateFlow()

    /**
     * 开始检测。会检查 RECORD_AUDIO 权限，无权限则 log 并返回。
     * 已在运行时调用会被忽略。
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (isActive) {
            Logging.log(TAG, "VAD already running, ignore start")
            return
        }
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Logging.log(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        isActive = true
        _hasAec.value = false // 录音源选定前为安全默认（未确认 AEC 时不信任）
        Logging.log(
            TAG,
            "VAD start: sampleRate=$sampleRate, frameMs=$frameDurationMs, " +
                "frameSamples=$frameSizeInSamples, threshold=$energyThreshold, " +
                "speechMs=$speechDurationMs, requiredFrames=$requiredSpeechFrames"
        )

        detectorJob = scope.launch {
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // 至少容纳一帧（*2 bytes/short）并留出余量
            val bufferSize = minBufferSize
                .coerceAtLeast(frameSizeInSamples * 2)
                .coerceAtLeast(4096)

            var recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
            audioRecord = recorder

            // 检查 AudioRecord 是否初始化成功，未成功则回退到 MIC 源重试
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Logging.log(
                    TAG,
                    "AudioRecord not initialized (state=${recorder.state}), retrying with MIC source"
                )
                runCatching { recorder.release() }
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
                )
                audioRecord = recorder
                _hasAec.value = false // MIC 源无硬件 AEC，TTS 回声会被收录并误触 barge-in
            } else {
                _hasAec.value = true // VOICE_COMMUNICATION 源启用硬件 AEC
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Logging.log(TAG, "AudioRecord still not initialized (state=${recorder.state})")
                runCatching { recorder.release() }
                audioRecord = null
                isActive = false
                return@launch
            }

            // 无 AEC 时将阈值提高 3 倍，降低 TTS 回声误触发概率
            val effectiveThreshold = computeEffectiveThreshold(energyThreshold, _hasAec.value)
            Logging.log(
                TAG,
                "AudioRecord initialized: source=${recorder.audioSource}, " +
                    "hasAec=${_hasAec.value}, sampleRate=$sampleRate, " +
                    "frameSamples=$frameSizeInSamples, bufferSize=$bufferSize, " +
                    "effectiveThreshold=${"%.2f".format(effectiveThreshold)}"
            )

            val buffer = ShortArray(frameSizeInSamples)
            var consecutiveSpeechFrames = 0
            // 环境噪声校准: 前 requiredSpeechFrames 帧测量噪声基线（max RMS），不触发检测
            var noiseFloor = 0.0
            var calibrationFrameCount = 0
            var calibratedThreshold = effectiveThreshold

            try {
                recorder.startRecording()
                Logging.log(TAG, "AudioRecord started recording")

                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val rms = calculateRms(buffer, read)

                        // 校准阶段: 测量环境噪声 RMS 基线，不检查 consecutiveSpeechFrames
                        if (calibrationFrameCount < requiredSpeechFrames) {
                            if (rms > noiseFloor) {
                                noiseFloor = rms
                            }
                            calibrationFrameCount++
                            if (calibrationFrameCount == requiredSpeechFrames) {
                                calibratedThreshold = computeCalibratedThreshold(effectiveThreshold, noiseFloor)
                                if (noiseFloor > energyThreshold) {
                                    Logging.log(
                                        TAG,
                                        "Warning: high noise floor during calibration: " +
                                            "noiseFloor=${"%.2f".format(noiseFloor)} > threshold=$energyThreshold"
                                    )
                                }
                                Logging.log(
                                    TAG,
                                    "Calibration done: noiseFloor=${"%.2f".format(noiseFloor)}, " +
                                        "finalThreshold=${"%.2f".format(calibratedThreshold)} " +
                                        "(effectiveThreshold=${"%.2f".format(effectiveThreshold)})"
                                )
                            }
                            continue
                        }

                        if (rms >= calibratedThreshold) {
                            consecutiveSpeechFrames++
                            if (consecutiveSpeechFrames >= requiredSpeechFrames) {
                                Logging.log(
                                    TAG,
                                    "Speech detected: rms=${"%.2f".format(rms)} " +
                                        "(threshold=${"%.2f".format(calibratedThreshold)}), " +
                                        "frames=$consecutiveSpeechFrames"
                                )
                                // one-shot: 先标记停止，再触发回调
                                isActive = false
                                runCatching { onSpeechDetected() }
                                    .onFailure {
                                        Logging.log(TAG, "onSpeechDetected callback threw: ${it.message}")
                                    }
                                break
                            }
                        } else {
                            consecutiveSpeechFrames = 0
                        }
                    } else if (read < 0) {
                        Logging.log(TAG, "AudioRecord read error: $read")
                        break
                    }
                }
            } catch (e: Exception) {
                Logging.log(TAG, "VAD recording failed: ${e.message}")
            } finally {
                releaseRecorder()
                isActive = false
            }
        }
    }

    /**
     * 停止检测并释放 AudioRecord。可重复调用。
     */
    fun stop() {
        if (!isActive && detectorJob == null) return
        Logging.log(TAG, "VAD stop called")
        isActive = false
        _hasAec.value = false
        detectorJob?.cancel()
        detectorJob = null
        releaseRecorder()
    }

    private fun releaseRecorder() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        _hasAec.value = false
    }
}

/** 计算一帧 PCM 16bit 样本的 RMS：sqrt(sum(sample^2) / n)。internal 以便单测覆盖。 */
internal fun calculateRms(buffer: ShortArray, count: Int): Double {
    if (count <= 0) return 0.0
    var sum = 0.0
    for (i in 0 until count) {
        val sample = buffer[i].toDouble()
        sum += sample * sample
    }
    return sqrt(sum / count)
}

/**
 * 计算有效阈值：无 AEC 时将基础阈值提高 3 倍。
 *
 * 无 AEC（回退到 MIC 源）时 TTS 扬声器声会被收录，需要更高阈值
 * 降低回声误触发概率。有 AEC（VOICE_COMMUNICATION 源）时阈值不变。
 *
 * internal 以便单测覆盖。
 */
internal fun computeEffectiveThreshold(energyThreshold: Double, hasAec: Boolean): Double {
    return if (hasAec) energyThreshold else energyThreshold * 3
}

/**
 * 计算校准后最终阈值：max(effectiveThreshold, noiseFloor * 2.5)。
 *
 * [noiseFloor] 为校准期间测得的最大环境噪声 RMS。若环境噪声较高，
 * 最终阈值会自动抬高到噪声基线的 2.5 倍，避免持续噪声误触发。
 *
 * internal 以便单测覆盖。
 */
internal fun computeCalibratedThreshold(effectiveThreshold: Double, noiseFloor: Double): Double {
    return maxOf(effectiveThreshold, noiseFloor * 2.5)
}

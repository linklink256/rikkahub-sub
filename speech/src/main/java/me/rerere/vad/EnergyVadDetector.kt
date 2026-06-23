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
    private val energyThreshold: Double = 3000.0,
    private val speechDurationMs: Int = 480,
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
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Logging.log(TAG, "AudioRecord still not initialized (state=${recorder.state})")
                runCatching { recorder.release() }
                audioRecord = null
                isActive = false
                return@launch
            }

            Logging.log(
                TAG,
                "AudioRecord initialized: source=${recorder.audioSource}, " +
                    "sampleRate=$sampleRate, frameSamples=$frameSizeInSamples, bufferSize=$bufferSize"
            )

            val buffer = ShortArray(frameSizeInSamples)
            var consecutiveSpeechFrames = 0

            try {
                recorder.startRecording()
                Logging.log(TAG, "AudioRecord started recording")

                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val rms = calculateRms(buffer, read)
                        if (rms >= energyThreshold) {
                            consecutiveSpeechFrames++
                            if (consecutiveSpeechFrames >= requiredSpeechFrames) {
                                Logging.log(
                                    TAG,
                                    "Speech detected: rms=${"%.2f".format(rms)} " +
                                        "(threshold=$energyThreshold), frames=$consecutiveSpeechFrames"
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
        detectorJob?.cancel()
        detectorJob = null
        releaseRecorder()
    }

    private fun releaseRecorder() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    /** 计算一帧 PCM 16bit 样本的 RMS：sqrt(sum(sample^2) / n) */
    private fun calculateRms(buffer: ShortArray, count: Int): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / count)
    }
}

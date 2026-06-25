package me.rerere.tts.controller

import android.content.Context
import android.media.AudioTrack
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.common.audio.pcm16ToWav
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AudioPlayer(context: Context) {
    @OptIn(UnstableApi::class)
    private val player = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var positionJob: Job? = null

    fun pause() = player.pause()
    fun resume() = player.play()
    fun stop() = player.stop()
    fun clear() = player.clearMediaItems()
    fun release() = player.release()
    fun seekBy(ms: Long) = player.seekTo(player.currentPosition + ms)
    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
        _playbackState.update { it.copy(speed = speed) }
    }

    @OptIn(UnstableApi::class)
    suspend fun play(response: TTSResponse) = suspendCancellableCoroutine<Unit> { cont ->
        val bytes = if (response.format == AudioFormat.PCM) {
            pcm16ToWav(response.audioData, response.sampleRate ?: 24000, channels = 1, bitsPerSample = 16)
        } else response.audioData

        val dataSourceFactory = DataSource.Factory { ByteArrayDataSource(bytes) }
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY))

        player.setMediaSource(mediaSource)
        player.prepare()
        player.play()

        _playbackState.update {
            it.copy(
                status = PlaybackStatus.Buffering,
                positionMs = 0L,
                durationMs = (response.duration?.times(1000))?.toLong() ?: it.durationMs
            )
        }

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        _playbackState.update { it.copy(status = PlaybackStatus.Buffering) }
                        stopPositionUpdates()
                    }
                    Player.STATE_READY -> {
                        val isPlaying = player.isPlaying
                        val duration = if (player.duration > 0) player.duration else playbackState.value.durationMs
                        _playbackState.update {
                            it.copy(
                                status = if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused,
                                durationMs = duration,
                                positionMs = player.currentPosition
                            )
                        }
                        if (isPlaying) startPositionUpdates() else stopPositionUpdates()
                    }
                    Player.STATE_ENDED -> {
                        stopPositionUpdates()
                        _playbackState.update {
                            it.copy(
                                status = PlaybackStatus.Ended,
                                positionMs = player.duration.coerceAtLeast(it.positionMs),
                                durationMs = if (player.duration > 0) player.duration else it.durationMs
                            )
                        }
                        player.removeListener(this)
                        if (cont.isActive) cont.resume(Unit)
                    }
                    Player.STATE_IDLE -> {
                        stopPositionUpdates()
                        _playbackState.update { it.copy(status = PlaybackStatus.Idle) }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                player.removeListener(this)
                stopPositionUpdates()
                _playbackState.update { it.copy(status = PlaybackStatus.Error, errorMessage = error.message) }
                if (cont.isActive) cont.resumeWithException(error)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val status = if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused
                _playbackState.update { it.copy(status = status) }
                if (isPlaying) startPositionUpdates() else stopPositionUpdates()
            }
        }
        player.addListener(listener)
        cont.invokeOnCancellation {
            player.removeListener(listener)
            player.stop()
            stopPositionUpdates()
        }
    }

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch(Dispatchers.Main.immediate) {
            while (true) {
                _playbackState.update {
                    it.copy(
                        positionMs = player.currentPosition,
                        durationMs = if (player.duration > 0) player.duration else it.durationMs
                    )
                }
                delay(100)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    /**
     * 流式播放: 根据 provider 返回的 Flow<AudioChunk> 自动选择播放方式。
     *
     * - PCM 格式: 用 AudioTrack 边收边播, 首音延迟 ≈ 第一个 chunk 到达时间
     * - MP3/WAV 等压缩格式: 收集完整数据后用 ExoPlayer 播放 (同 [play])
     *
     * AudioTrack.write 在 IO 线程执行, 不阻塞 Main 线程。
     * 协程取消时 finally 释放 AudioTrack。
     */
    @OptIn(UnstableApi::class)
    suspend fun playAudioFlow(flow: Flow<AudioChunk>) {
        var format: AudioFormat? = null
        var sampleRate: Int? = null
        var audioTrack: AudioTrack? = null
        val output = ByteArrayOutputStream()

        try {
            withContext(Dispatchers.IO) {
                flow.collect { chunk ->
                    if (format == null) {
                        // 第一个 chunk: 决定播放方式
                        format = chunk.format
                        sampleRate = chunk.sampleRate
                        if (format == AudioFormat.PCM && sampleRate != null) {
                            // PCM: 初始化 AudioTrack 流式播放
                            val sr = sampleRate!!
                            val minBufSize = AudioTrack.getMinBufferSize(
                                sr,
                                android.media.AudioFormat.CHANNEL_OUT_MONO,
                                android.media.AudioFormat.ENCODING_PCM_16BIT
                            )
                            audioTrack = AudioTrack.Builder()
                                .setAudioAttributes(
                                    android.media.AudioAttributes.Builder()
                                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build()
                                )
                                .setAudioFormat(
                                    android.media.AudioFormat.Builder()
                                        .setSampleRate(sr)
                                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                                        .build()
                                )
                                .setBufferSizeInBytes(minBufSize.coerceAtLeast(4096))
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .build()
                            audioTrack?.play()
                            _playbackState.update {
                                it.copy(status = PlaybackStatus.Playing, positionMs = 0L)
                            }
                        } else {
                            // MP3/WAV: 先收集, 后用 ExoPlayer 播放
                            _playbackState.update { it.copy(status = PlaybackStatus.Buffering) }
                        }
                    }

                    val track = audioTrack
                    if (track != null) {
                        track.write(chunk.data, 0, chunk.data.size)
                    } else {
                        output.write(chunk.data)
                    }
                }

                // Flow 收集完成
                audioTrack?.let { track ->
                    track.stop()
                    _playbackState.update { it.copy(status = PlaybackStatus.Ended) }
                }
            }

            // 非 PCM 格式: 用 ExoPlayer 播放收集的完整数据
            if (audioTrack == null && output.size() > 0) {
                val response = TTSResponse(
                    audioData = output.toByteArray(),
                    format = format ?: AudioFormat.MP3,
                    sampleRate = sampleRate
                )
                play(response)
            }
        } finally {
            runCatching { audioTrack?.stop() }
            runCatching { audioTrack?.release() }
        }
    }
}


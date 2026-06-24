package me.rerere.asr

import kotlinx.coroutines.flow.StateFlow

interface ASRController {
    val state: StateFlow<ASRState>
    fun start(
        onTranscriptChange: (String) -> Unit,
        onTranscriptComplete: ((String) -> Unit)? = null
    )
    fun stop()
    fun dispose()
}

package me.rerere.rikkahub.ui.pages.voicecall

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

/**
 * 语音通话状态机
 */
enum class VoiceCallStatus {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ENDED,
}

/**
 * 语音通话 UI 状态
 */
data class VoiceCallState(
    val status: VoiceCallStatus = VoiceCallStatus.IDLE,
    val userTranscript: String = "",
    val assistantText: String = "",
    val errorMessage: String? = null,
    val callDurationMs: Long = 0L,
)

/**
 * 实时语音通话 ViewModel
 *
 * 管理 ASR(语音转文字) → LLM(聊天) → TTS(文字转语音) 的半双工循环状态。
 * 仅负责状态管理与 LLM 调用, ASR/TTS 的具体控制由 VoiceCallPage 通过 CompositionLocal 编排。
 */
class VoiceCallVM(
    private val conversationId: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(conversationId)

    private val _state = MutableStateFlow(VoiceCallState())
    val state: StateFlow<VoiceCallState> = _state.asStateFlow()

    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val currentChatModel: StateFlow<Model?> =
        settings.map { it.getCurrentChatModel() }
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    val errors: StateFlow<List<ChatError>> = chatService.errors

    private var timerJob: Job? = null

    // 记录发送前的消息数, 用于判断生成后是否有新增 ASSISTANT 消息
    private var messageCountBeforeSend: Int = 0

    init {
        Logging.log("VoiceCall", "Call init, conversationId=$conversationId")
        chatService.addConversationReference(_conversationId)
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }
    }

    fun updateStatus(status: VoiceCallStatus) {
        Logging.log("VoiceCall", "Status: ${_state.value.status} -> $status")
        _state.update { it.copy(status = status) }
    }

    fun updateUserTranscript(text: String) {
        _state.update { it.copy(userTranscript = text) }
    }

    fun updateAssistantText(text: String) {
        _state.update { it.copy(assistantText = text) }
    }

    fun setError(msg: String?) {
        _state.update { it.copy(errorMessage = msg) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * 发送用户消息并进入 THINKING 状态。
     */
    fun sendUserMessage(text: String) {
        if (text.isBlank()) return
        Logging.log("VoiceCall", "Send user message: $text")
        messageCountBeforeSend = conversation.value.currentMessages.size
        chatService.sendMessage(_conversationId, listOf(UIMessagePart.Text(text)))
        _state.update {
            it.copy(status = VoiceCallStatus.THINKING, userTranscript = text)
        }
    }

    /**
     * 从当前对话中取本轮新生成的最后一条 ASSISTANT 消息的纯文本。
     * 只查找 [messageCountBeforeSend] 之后的消息, 避免朗读上一轮的旧回复。
     */
    fun getLatestAssistantText(): String? {
        val text = conversation.value.currentMessages
            .drop(messageCountBeforeSend)
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.takeIf { it.toText().isNotBlank() }
            ?.toText()
        Logging.log("VoiceCall", "Latest assistant text: $text")
        return text
    }

    fun startCallTimer() {
        timerJob?.cancel()
        _state.update { it.copy(callDurationMs = 0L) }
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _state.update { it.copy(callDurationMs = it.callDurationMs + 1000) }
            }
        }
    }

    fun stopCallTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        Logging.log("VoiceCall", "Call onCleared")
        timerJob?.cancel()
        chatService.removeConversationReference(_conversationId)
    }
}

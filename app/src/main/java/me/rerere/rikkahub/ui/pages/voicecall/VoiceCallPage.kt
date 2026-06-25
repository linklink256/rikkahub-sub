package me.rerere.rikkahub.ui.pages.voicecall

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid
import me.rerere.asr.ASRProviderSetting
import me.rerere.common.android.Logging
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Voice
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.pages.chat.AssistantBackground
import me.rerere.tts.model.PlaybackStatus
import me.rerere.vad.EnergyVadDetector
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private val InterruptOrange = Color(0xFFFFA000)

/**
 * 实时语音通话页面
 *
 * 实现 ASR(语音转文字) -> LLM(聊天) -> TTS(文字转语音) 的半双工循环。
 * 状态机流转由 [VoiceCallVM] 管理, ASR/TTS 的具体控制通过 CompositionLocal 编排。
 */
@Composable
fun VoiceCallPage(conversationId: String) {
    val vm: VoiceCallVM = koinViewModel(
        parameters = { parametersOf(conversationId) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val setting by vm.settings.collectAsStateWithLifecycle()

    val asr = LocalASRState.current
    val tts = LocalTTSState.current
    val asrState by asr.state.collectAsStateWithLifecycle()
    val ttsPlaybackState by tts.playbackState.collectAsStateWithLifecycle()
    val ttsIsSpeaking by tts.isSpeaking.collectAsStateWithLifecycle()

    val navController = LocalNavController.current
    val toaster = LocalToaster.current

    val conversationUuid = remember { Uuid.parse(conversationId) }

    // 流式 TTS 状态
    var streamCycle by remember { mutableStateOf(0) }
    val generationDone = remember { mutableStateOf(false) }

    val context = LocalContext.current

    // 能量 VAD: SPEAKING 时监听用户说话, 实现 barge-in (全双工打断)
    val energyVad = remember {
        EnergyVadDetector(
            context = context.applicationContext,
            energyThreshold = 3000.0,
            speechDurationMs = 480,
            onSpeechDetected = {
                Logging.log("VoiceCall", "VAD barge-in: user speech detected during AI response")
                vm.stopGeneration()
                tts.stop()
                vm.updateStatus(VoiceCallStatus.LISTENING)
            }
        )
    }

    val asrPermission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = asrPermission)

    // ---- 状态机编排 ----

    // 进入 LISTENING 启动 ASR, 离开时停止 ASR
    LaunchedEffect(state.status) {
        if (state.status == VoiceCallStatus.LISTENING) {
            Logging.log("VoiceCall", "Entering LISTENING, starting ASR")
            asr.start(
                onTranscriptChange = { transcript ->
                    vm.updateUserTranscript(transcript)
                },
                onTranscriptComplete = { finalTranscript ->
                    Logging.log("VoiceCall", "ASR transcript complete: $finalTranscript")
                    if (finalTranscript.isNotBlank() && vm.state.value.status == VoiceCallStatus.LISTENING) {
                        vm.updateUserTranscript(finalTranscript)
                        vm.sendUserMessage(finalTranscript)
                    }
                }
            )
        } else {
            Logging.log("VoiceCall", "Leaving LISTENING, stopping ASR")
            asr.stop()
        }
    }

    // 长驻收集器 — 标记生成完成
    LaunchedEffect(Unit) {
        vm.generationDoneFlow.collect { doneUuid ->
            if (doneUuid == conversationUuid) {
                generationDone.value = true
                Logging.log("VoiceCall", "Generation done flag set")
            }
        }
    }

    // 流式 TTS: 实时监听对话更新, 累积到句末标点或足够长度后再送入 TTS
    LaunchedEffect(streamCycle) {
        val thisCycle = streamCycle
        var lastAssistantIndex = -1
        var lastTextLength = 0
        var pendingBuffer = StringBuilder()
        var finalFlushDone = false
        var lastFlushTime = System.currentTimeMillis()
        // 断句标点: 句末标点(。！？\n) + 子句分隔符(，,;；:：)
        // 参考 bailing 项目: 逗号也是有效切分点, 让 TTS 在子句边界尽早播报,
        // 而不是等到完整句末才送 TTS (降低首音延迟, 断句更自然)。
        val sentenceEndRegex = Regex("[。！？\\n，,;；:：]")
        val minChunkSize = 30  // 最少累积 30 字再发送, 避免断句
        Logging.log("VoiceCall", "Streaming TTS started, cycle=$thisCycle")

        // 子协程: 监听 generationDone 信号, 强制 flush buffer 中残余内容
        // (不依赖后续 conversation emission, 修复非标点结尾回复尾部漏读问题)
        launch {
            vm.generationDoneFlow.first { it == conversationUuid }
            if (streamCycle != thisCycle) return@launch
            generationDone.value = true
            val s = vm.state.value.status
            if (s != VoiceCallStatus.THINKING && s != VoiceCallStatus.SPEAKING) return@launch
            if (pendingBuffer.isNotEmpty() && !finalFlushDone) {
                finalFlushDone = true
                val remaining = pendingBuffer.toString()
                Logging.log("VoiceCall", "TTS final flush on generationDone (${remaining.length}c): ${remaining.take(50)}...")
                val isFirstChunk = s == VoiceCallStatus.THINKING
                tts.speak(remaining, flushCalled = isFirstChunk)
                pendingBuffer.clear()
                if (isFirstChunk) {
                    vm.updateStatus(VoiceCallStatus.SPEAKING)
                }
            }
        }

        vm.conversation.collect { conv ->
            if (streamCycle != thisCycle) return@collect
            val s = vm.state.value.status
            if (s != VoiceCallStatus.THINKING && s != VoiceCallStatus.SPEAKING) return@collect

            val assistantIndex = conv.currentMessages.indexOfLast { it.role == MessageRole.ASSISTANT }
            val text = if (assistantIndex >= 0) conv.currentMessages[assistantIndex].toText() else ""

            // 新消息出现 → 重置追踪 (跳过已有文本, 只追踪后续增长)
            if (assistantIndex != lastAssistantIndex) {
                lastAssistantIndex = assistantIndex
                lastTextLength = text.length  // 把当前文本标记为"已处理", 只追踪新增的
                pendingBuffer.clear()
                lastFlushTime = System.currentTimeMillis()
            }

            if (text.length > lastTextLength) {
                val delta = text.substring(lastTextLength)
                lastTextLength = text.length
                if (delta.isNotBlank()) {
                    pendingBuffer.append(delta)
                    val pending = pendingBuffer.toString()

                    // 遇到句末标点 或 累积足够长 → 发送给 TTS
                    val shouldFlush = sentenceEndRegex.containsMatchIn(pending) || pending.length >= minChunkSize
                    // 如果生成已完成, 最后剩余的也要发送
                    val isFinal = generationDone.value

                    if (shouldFlush || isFinal) {
                        // 在标点处分句: 发到最后一个标点为止, 剩余留在 buffer
                        val flushText: String
                        val remaining: String
                        if (isFinal) {
                            flushText = pending
                            remaining = ""
                        } else {
                            val lastEnd = sentenceEndRegex.findAll(pending).lastOrNull()
                            if (lastEnd != null) {
                                val splitAt = lastEnd.range.last + 1
                                flushText = pending.substring(0, splitAt)
                                remaining = pending.substring(splitAt)
                            } else {
                                // 没有句末标点但超长了, 全部发送
                                flushText = pending
                                remaining = ""
                            }
                        }

                        if (flushText.isNotBlank()) {
                            // 最小字数检查: 非 final 时过短的内容(如单独标点)保留在 buffer, 等待更多文本
                            if (flushText.length < 2 && !isFinal) {
                                Logging.log("VoiceCall", "TTS skip short chunk (${flushText.length}c), waiting for more")
                            } else {
                                val isFirstChunk = s == VoiceCallStatus.THINKING
                                Logging.log(
                                    "VoiceCall",
                                    "TTS flush (${flushText.length}c, first=$isFirstChunk): ${flushText.take(50)}..."
                                )
                                tts.speak(flushText, flushCalled = isFirstChunk)
                                pendingBuffer = StringBuilder(remaining)
                                lastFlushTime = System.currentTimeMillis()
                                vm.updateAssistantText(text)
                                if (isFirstChunk) {
                                    vm.updateStatus(VoiceCallStatus.SPEAKING)
                                }
                            }
                        }
                    }
                }
            }

            // 超时强制 flush: buffer 非空且超过 1.5 秒未 flush 时, 即使无标点也发送
            // (降低无标点长定语的首音延迟)
            if (pendingBuffer.isNotEmpty() && !generationDone.value &&
                System.currentTimeMillis() - lastFlushTime > 1500
            ) {
                val pending = pendingBuffer.toString()
                if (pending.length >= 2) {  // 仍需满足最小字数
                    val isFirstChunk = s == VoiceCallStatus.THINKING
                    Logging.log(
                        "VoiceCall",
                        "TTS timeout flush (${pending.length}c, first=$isFirstChunk): ${pending.take(50)}..."
                    )
                    tts.speak(pending, flushCalled = isFirstChunk)
                    pendingBuffer.clear()
                    lastFlushTime = System.currentTimeMillis()
                    vm.updateAssistantText(text)
                    if (isFirstChunk) {
                        vm.updateStatus(VoiceCallStatus.SPEAKING)
                    }
                }
            }

            // 生成完成时确保 buffer 中剩余内容也发送 (只执行一次)
            if (generationDone.value && !finalFlushDone) {
                finalFlushDone = true
                if (pendingBuffer.isNotEmpty()) {
                    val remaining = pendingBuffer.toString()
                    Logging.log("VoiceCall", "TTS final flush (${remaining.length}c): ${remaining.take(50)}...")
                    tts.speak(remaining, flushCalled = false)
                    pendingBuffer.clear()
                }
            }
        }
    }

    // THINKING 处理: 启动流式 TTS + 超时保护
    LaunchedEffect(state.status) {
        if (state.status == VoiceCallStatus.THINKING) {
            Logging.log("VoiceCall", "Entering THINKING, starting streaming cycle")
            generationDone.value = false
            streamCycle++  // 触发新的流式 TTS 周期

            // 超时保护: 若 120 秒内状态未从 THINKING 切走
            withTimeoutOrNull(120_000L) {
                vm.state.first { it.status != VoiceCallStatus.THINKING }
            } ?: run {
                if (vm.state.value.status == VoiceCallStatus.THINKING) {
                    Logging.log("VoiceCall", "Generation timeout (120s)")
                    toaster.show(
                        message = "生成超时, 请重试",
                        type = ToastType.Warning,
                    )
                    vm.updateStatus(VoiceCallStatus.LISTENING)
                }
            }
        }
    }

    // SPEAKING: TTS 全部播放结束后自动回到 LISTENING, 出错时提示并回到监听
    // 注意: 用 tts.isSpeaking == false 判断"全部块播完"，而非 PlaybackStatus.Ended
    // (Ended 是每块结束信号，块间 120ms gap 会误触发提前切回 LISTENING 导致截断+串话)
    LaunchedEffect(ttsIsSpeaking, ttsPlaybackState.status) {
        if (state.status == VoiceCallStatus.SPEAKING) {
            when {
                ttsPlaybackState.status == PlaybackStatus.Error -> {
                    Logging.log("VoiceCall", "TTS error, back to LISTENING")
                    toaster.show(
                        message = "语音合成出错",
                        type = ToastType.Error,
                    )
                    tts.stop()
                    vm.updateStatus(VoiceCallStatus.LISTENING)
                }
                !ttsIsSpeaking && generationDone.value -> {
                    Logging.log("VoiceCall", "TTS all chunks done, generationDone=true, back to LISTENING")
                    tts.stop()
                    vm.updateStatus(VoiceCallStatus.LISTENING)
                }
            }
        }
    }

    // SPEAKING 超时保护: 若 120 秒内状态未从 SPEAKING 切走 (兜底: TTS 早退/合成失败未触发 isSpeaking 变化时)
    LaunchedEffect(state.status) {
        if (state.status == VoiceCallStatus.SPEAKING) {
            withTimeoutOrNull(120_000L) {
                vm.state.first { it.status != VoiceCallStatus.SPEAKING }
            } ?: run {
                if (vm.state.value.status == VoiceCallStatus.SPEAKING) {
                    Logging.log("VoiceCall", "SPEAKING timeout (120s), back to LISTENING")
                    tts.stop()
                    vm.updateStatus(VoiceCallStatus.LISTENING)
                }
            }
        }
    }

    // ASR 错误提示
    LaunchedEffect(asrState.errorMessage) {
        if (!asrState.errorMessage.isNullOrBlank()) {
            val message = "语音识别出错: ${asrState.errorMessage}"
            toaster.show(
                message = message,
                type = ToastType.Error,
            )
            Logging.log("VoiceCall", "ASR error, resetting to IDLE: ${asrState.errorMessage}")
            vm.updateStatus(VoiceCallStatus.IDLE)
        }
    }

    // LLM 错误兜底: 生成出错时 TTS 播一句提示并回到监听
    LaunchedEffect(vm.errors) {
        val processedErrorIds = mutableSetOf<Uuid>()
        vm.errors.collect { errors ->
            // 只处理本会话最新的未处理错误
            val myError = errors.lastOrNull {
                it.conversationId == conversationUuid && it.id !in processedErrorIds
            }
            if (myError != null) {
                processedErrorIds.add(myError.id)
                // 仅在 THINKING 状态兜底 (SPEAKING 说明 TTS 已开始, 不再重复兜底)
                if (vm.state.value.status == VoiceCallStatus.THINKING) {
                    val errorMsg = myError.error.message ?: "未知错误"
                    Logging.log("VoiceCall", "LLM error: $errorMsg")
                    toaster.show(message = "AI 回复出错: $errorMsg", type = ToastType.Error)
                    // 用 SPEAKING 播放道歉语音, 播完后通过正常 SPEAKING→LISTENING 流程回到监听
                    // 避免道歉语音播放期间 ASR 已启动导致串录道歉语形成反馈环
                    generationDone.value = true
                    tts.speak("抱歉，出了点问题，请再说一次", flushCalled = true)
                    vm.updateStatus(VoiceCallStatus.SPEAKING)
                }
            }
        }
    }

    // 全双工 barge-in: THINKING/SPEAKING 时启动能量 VAD 监听用户说话
    LaunchedEffect(state.status) {
        when (state.status) {
            VoiceCallStatus.THINKING, VoiceCallStatus.SPEAKING -> {
                Logging.log("VoiceCall", "Starting energy VAD for barge-in (${state.status})")
                energyVad.stop()
                energyVad.start()
            }
            else -> {
                energyVad.stop()
            }
        }
    }

    // 离开页面时释放 VAD
    DisposableEffect(Unit) {
        onDispose {
            energyVad.stop()
        }
    }

    // ---- 按钮动作 ----

    val startCall: () -> Unit = {
        val asrProvider = setting.getSelectedASRProvider()
        when {
            asrProvider == null -> toaster.show(
                message = "语音识别未就绪, 请先在设置中配置 ASR",
                type = ToastType.Warning,
            )

            setting.getSelectedTTSProvider() == null -> toaster.show(
                message = "语音合成未就绪, 请先在设置中配置 TTS",
                type = ToastType.Warning,
            )

            !asrPermission.allRequiredPermissionsGranted -> asrPermission.requestPermissions()

            else -> {
                vm.startCallTimer()
                vm.updateStatus(VoiceCallStatus.LISTENING)
            }
        }
    }

    val stopAndSend: () -> Unit = {
        Logging.log("VoiceCall", "Stop and send")
        val text = vm.state.value.userTranscript
        if (text.isNotBlank()) {
            asr.stop()
            vm.sendUserMessage(text)
        } else {
            Logging.log("VoiceCall", "Stop and send with empty transcript, ignoring")
            toaster.show(
                message = "没有听到声音，请再说一次",
                type = ToastType.Warning,
            )
        }
    }

    val interrupt: () -> Unit = {
        Logging.log("VoiceCall", "Interrupt clicked")
        vm.stopGeneration()
        tts.stop()
        vm.updateStatus(VoiceCallStatus.LISTENING)
    }

    val hangup: () -> Unit = {
        Logging.log("VoiceCall", "Hangup clicked")
        asr.stop()
        tts.stop()
        energyVad.stop()
        vm.stopGeneration()
        vm.stopCallTimer()
        vm.updateStatus(VoiceCallStatus.ENDED)
        navController.popBackStack()
    }

    // 返回键视为挂断, 确保 ASR/TTS 资源被释放
    BackHandler {
        hangup()
    }

    val statusLabel = when (state.status) {
        VoiceCallStatus.IDLE -> "点击开始通话"
        VoiceCallStatus.LISTENING -> "正在聆听..."
        VoiceCallStatus.THINKING -> "思考中..."
        VoiceCallStatus.SPEAKING -> "正在播报..."
        VoiceCallStatus.ENDED -> "通话已结束"
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize()) {
            AssistantBackground(setting = setting, modifier = Modifier.fillMaxSize())

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            Spacer(Modifier.height(56.dp))

            // 通话时长
            Text(
                text = formatDuration(state.callDurationMs),
                color = Color.White,
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(40.dp))

            // 中央动态指示器
            VoiceCallIndicator(status = state.status)

            Spacer(Modifier.height(24.dp))

            // 状态文字
            Text(
                text = statusLabel,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            // 最近对话显示区
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.userTranscript.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Surface(
                            modifier = Modifier.widthIn(max = 280.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White.copy(alpha = 0.15f),
                        ) {
                            Text(
                                text = state.userTranscript,
                                color = Color.White,
                                modifier = Modifier.padding(12.dp),
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (state.assistantText.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        Surface(
                            modifier = Modifier.widthIn(max = 280.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        ) {
                            Text(
                                text = state.assistantText,
                                color = Color.White,
                                modifier = Modifier.padding(12.dp),
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // 底部按钮区
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state.status) {
                VoiceCallStatus.IDLE -> {
                    CallActionButton(
                        icon = HugeIcons.Voice,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        contentDescription = "开始通话",
                        onClick = startCall,
                    )
                    CallActionButton(
                        icon = HugeIcons.Cancel01,
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        contentDescription = "挂断",
                        onClick = hangup,
                    )
                }

                VoiceCallStatus.LISTENING -> {
                    CallActionButton(
                        icon = HugeIcons.Cancel01,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        contentDescription = "停止并发送",
                        onClick = stopAndSend,
                    )
                    CallActionButton(
                        icon = HugeIcons.Cancel01,
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        contentDescription = "挂断",
                        onClick = hangup,
                    )
                }

                VoiceCallStatus.THINKING, VoiceCallStatus.SPEAKING -> {
                    CallActionButton(
                        icon = HugeIcons.Cancel01,
                        containerColor = InterruptOrange,
                        contentColor = Color.Black,
                        contentDescription = "打断",
                        onClick = interrupt,
                    )
                    CallActionButton(
                        icon = HugeIcons.Cancel01,
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        contentDescription = "挂断",
                        onClick = hangup,
                    )
                }

                VoiceCallStatus.ENDED -> {
                    CallActionButton(
                        icon = HugeIcons.Cancel01,
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        contentDescription = "挂断",
                        onClick = hangup,
                    )
                }
            }
        }
    }
    }
}

/**
 * 通话时长格式化 (mm:ss)
 */
private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/**
 * 中央动态指示器, 根据 [status] 显示不同动画
 */
@Composable
private fun VoiceCallIndicator(
    status: VoiceCallStatus,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(160.dp), contentAlignment = Alignment.Center) {
        when (status) {
            VoiceCallStatus.IDLE, VoiceCallStatus.ENDED -> {
                Surface(
                    modifier = Modifier.size(96.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.12f),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = HugeIcons.Voice,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }

            VoiceCallStatus.LISTENING -> {
                val transition = rememberInfiniteTransition(label = "listening")
                val scale by transition.animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1.25f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "listening_scale",
                )
                val alpha by transition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 0.15f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "listening_alpha",
                )
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                )
                Surface(
                    modifier = Modifier.size(96.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = HugeIcons.Voice,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }

            VoiceCallStatus.THINKING -> {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(64.dp),
                )
            }

            VoiceCallStatus.SPEAKING -> {
                val transition = rememberInfiniteTransition(label = "speaking")
                val scale1 by transition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1.3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "speaking_scale1",
                )
                val alpha1 by transition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "speaking_alpha1",
                )
                val scale2 by transition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1.3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, delayMillis = 600),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "speaking_scale2",
                )
                val alpha2 by transition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, delayMillis = 600),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "speaking_alpha2",
                )
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .graphicsLayer {
                            scaleX = scale1
                            scaleY = scale1
                            this.alpha = alpha1
                        }
                        .clip(CircleShape)
                        .border(2.dp, Color.White, CircleShape),
                )
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .graphicsLayer {
                            scaleX = scale2
                            scaleY = scale2
                            this.alpha = alpha2
                        }
                        .clip(CircleShape)
                        .border(2.dp, Color.White, CircleShape),
                )
                Surface(
                    modifier = Modifier.size(96.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = HugeIcons.Voice,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 圆形操作按钮
 */
@Composable
private fun CallActionButton(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(72.dp),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Voice
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

    val navController = LocalNavController.current
    val toaster = LocalToaster.current

    val conversationUuid = remember { Uuid.parse(conversationId) }

    val asrPermission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = asrPermission)

    // ---- 状态机编排 ----

    // 进入 LISTENING 启动 ASR, 离开时停止 ASR
    LaunchedEffect(state.status) {
        if (state.status == VoiceCallStatus.LISTENING) {
            asr.start { transcript ->
                vm.updateUserTranscript(transcript)
            }
        } else {
            asr.stop()
        }
    }

    // THINKING: 等待生成完成, 然后朗读 AI 回复并进入 SPEAKING
    LaunchedEffect(state.status) {
        if (state.status == VoiceCallStatus.THINKING) {
            // 超时保护: 若 generationDoneFlow 未发射 (如生成异常), 120 秒后回退
            val done = withTimeoutOrNull(120_000L) {
                vm.generationDoneFlow.first { it == conversationUuid }
            }
            if (done == null) {
                toaster.show(
                    message = "生成超时, 请重试",
                    type = ToastType.Warning,
                )
                vm.updateStatus(VoiceCallStatus.LISTENING)
                return@LaunchedEffect
            }
            val text = vm.getLatestAssistantText()
            if (!text.isNullOrBlank()) {
                vm.updateAssistantText(text)
                tts.speak(text)
                vm.updateStatus(VoiceCallStatus.SPEAKING)
            } else {
                // 无新回复, 可能是生成失败 — 检查错误并提示
                val error = vm.errors.value.lastOrNull { it.conversationId == conversationUuid }
                if (error != null) {
                    toaster.show(
                        message = error.title ?: "生成回复出错",
                        type = ToastType.Error,
                    )
                }
                vm.updateStatus(VoiceCallStatus.LISTENING)
            }
        }
    }

    // SPEAKING: TTS 播放结束后自动回到 LISTENING, 出错时提示并回到监听
    LaunchedEffect(ttsPlaybackState.status) {
        if (state.status == VoiceCallStatus.SPEAKING) {
            when (ttsPlaybackState.status) {
                PlaybackStatus.Ended -> vm.updateStatus(VoiceCallStatus.LISTENING)
                PlaybackStatus.Error -> {
                    toaster.show(
                        message = "语音合成出错",
                        type = ToastType.Error,
                    )
                    vm.updateStatus(VoiceCallStatus.LISTENING)
                }
                else -> {}
            }
        }
    }

    // ASR 错误提示
    LaunchedEffect(asrState.errorMessage) {
        if (!asrState.errorMessage.isNullOrBlank()) {
            toaster.show(
                message = "语音识别出错: ${asrState.errorMessage}",
                type = ToastType.Error,
            )
        }
    }

    // ---- 按钮动作 ----

    val startCall: () -> Unit = {
        when {
            setting.getSelectedASRProvider() == null -> toaster.show(
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
        asr.stop()
        val text = vm.state.value.userTranscript
        if (text.isNotBlank()) {
            vm.sendUserMessage(text)
        }
    }

    val interrupt: () -> Unit = {
        vm.stopGeneration()
        tts.stop()
        vm.updateStatus(VoiceCallStatus.LISTENING)
    }

    val hangup: () -> Unit = {
        asr.stop()
        tts.stop()
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

package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collect
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.subagent.SubagentProfile
import me.rerere.rikkahub.data.ai.subagent.mergeSubagentProfiles
import me.rerere.rikkahub.data.ai.subagent.upsertSubagentProfile
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.TextArea
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantSubagentProfilePage(id: String, profileName: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(profileName) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantSubagentProfileContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            providers = providers,
            profileName = profileName,
            onUpdate = { vm.update(it) },
        )
    }
}

@Composable
private fun AssistantSubagentProfileContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    profileName: String,
    onUpdate: (Assistant) -> Unit,
) {
    // 当前编辑的 profile：从合并后的列表里取（内置 + 自定义覆盖）
    val merged = mergeSubagentProfiles(assistant.subagentProfiles)
    val current = merged.firstOrNull { it.name == profileName } ?: SubagentProfile(name = profileName)

    // 用 rememberUpdatedState 持有最新 assistant，供长效协程（systemPrompt 的 snapshotFlow）读取，
    // 避免闭包捕获 stale assistant 导致并发编辑互相覆盖。
    val latestAssistant = rememberUpdatedState(assistant)

    fun saveProfile(transform: (SubagentProfile) -> SubagentProfile) {
        val updated = transform(current)
        onUpdate(
            assistant.copy(
                subagentProfiles = upsertSubagentProfile(assistant.subagentProfiles, updated)
            )
        )
    }

    /** 在协程里基于最新 assistant 保存 profile（避免 stale 覆盖） */
    fun saveProfileFromCoroutine(transform: (SubagentProfile) -> SubagentProfile) {
        val a = latestAssistant.value
        val cur = mergeSubagentProfiles(a.subagentProfiles).firstOrNull { it.name == profileName }
            ?: SubagentProfile(name = profileName)
        onUpdate(a.copy(subagentProfiles = upsertSubagentProfile(a.subagentProfiles, transform(cur))))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- 基本信息 ----
        Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // name（标识符，不可改 —— 改名等于新建另一个 profile）
                OutlinedTextField(
                    value = current.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.subagent_profile_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = current.displayName,
                    onValueChange = { v -> saveProfile { it.copy(displayName = v) } },
                    label = { Text(stringResource(R.string.subagent_profile_display_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = current.description,
                    onValueChange = { v -> saveProfile { it.copy(description = v) } },
                    label = { Text(stringResource(R.string.subagent_profile_description)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- 系统提示词 ----
        Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.subagent_profile_system_prompt),
                    style = MaterialTheme.typography.titleMedium,
                )
                val promptState = rememberTextFieldState(initialText = current.systemPrompt)
                LaunchedEffect(promptState) {
                    snapshotFlow { promptState.text }.collect { text ->
                        val v = text.toString()
                        saveProfileFromCoroutine { it.copy(systemPrompt = v) }
                    }
                }
                TextArea(
                    state = promptState,
                    label = stringResource(R.string.subagent_profile_system_prompt),
                    minLines = 6,
                    maxLines = 15,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- 模型与参数 ----
        Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormItem(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.subagent_profile_model)) },
                    description = { Text(stringResource(R.string.subagent_profile_model_desc)) },
                ) {
                    ModelSelector(
                        modelId = current.chatModelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        allowClear = true,
                        onSelect = { model ->
                            saveProfile { it.copy(chatModelId = model.id) }
                        },
                    )
                }
                HorizontalDivider()

                // temperature
                NumberField(
                    label = stringResource(R.string.subagent_profile_temperature),
                    value = current.temperature,
                    range = 0f..2f,
                    onValueChange = { v -> saveProfile { it.copy(temperature = v) } },
                )
                HorizontalDivider()

                // topP
                NumberField(
                    label = stringResource(R.string.subagent_profile_top_p),
                    value = current.topP,
                    range = 0f..1f,
                    onValueChange = { v -> saveProfile { it.copy(topP = v) } },
                )
                HorizontalDivider()

                // maxTokens
                NumberField(
                    label = stringResource(R.string.subagent_profile_max_tokens),
                    value = current.maxTokens?.toFloat(),
                    range = 1f..128000f,
                    isInt = true,
                    onValueChange = { v ->
                        saveProfile { it.copy(maxTokens = v?.toInt()) }
                    },
                )
                HorizontalDivider()

                // reasoningLevel
                FormItem(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    label = { Text(stringResource(R.string.subagent_profile_reasoning)) },
                ) {
                    ReasoningButton(
                        reasoningLevel = current.reasoningLevel,
                        onUpdateReasoningLevel = { level ->
                            saveProfile { it.copy(reasoningLevel = level) }
                        },
                    )
                }
            }
        }

        // ---- 行为参数 ----
        Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NumberField(
                    label = stringResource(R.string.subagent_profile_max_steps),
                    value = current.maxSteps.toFloat(),
                    range = 1f..256f,
                    isInt = true,
                    onValueChange = { v ->
                        if (v != null) saveProfile { it.copy(maxSteps = v.toInt().coerceIn(1, 256)) }
                    },
                )
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.subagent_profile_inherit_tools)) },
                    description = { Text(stringResource(R.string.subagent_profile_inherit_tools_desc)) },
                    tail = {
                        Switch(
                            checked = current.inheritTools,
                            onCheckedChange = { v -> saveProfile { it.copy(inheritTools = v) } },
                        )
                    },
                )
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.subagent_profile_stream)) },
                    description = { Text(stringResource(R.string.subagent_profile_stream_desc)) },
                    tail = {
                        Switch(
                            checked = current.streamOutput,
                            onCheckedChange = { v -> saveProfile { it.copy(streamOutput = v) } },
                        )
                    },
                )
                HorizontalDivider()
                FormItem(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.subagent_profile_memory)) },
                    description = { Text(stringResource(R.string.subagent_profile_memory_desc)) },
                    tail = {
                        Switch(
                            checked = current.enableMemory,
                            onCheckedChange = { v -> saveProfile { it.copy(enableMemory = v) } },
                        )
                    },
                )
                HorizontalDivider()
                NumberField(
                    label = stringResource(R.string.subagent_profile_summary_min_length),
                    value = current.summaryMinLength.toFloat(),
                    range = 0f..1000f,
                    isInt = true,
                    onValueChange = { v ->
                        if (v != null) saveProfile { it.copy(summaryMinLength = v.toInt().coerceIn(0, 1000)) }
                    },
                )
            }
        }
    }
}

/**
 * 通用数值输入字段（Float / Int），支持可空的"未设置"状态。
 */
@Composable
private fun NumberField(
    label: String,
    value: Float?,
    range: ClosedFloatingPointRange<Float>,
    isInt: Boolean = false,
    onValueChange: (Float?) -> Unit,
) {
    FormItem(
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
    ) {
        // 用稳定 key（label）避免 value 变化时重置文本，否则 Float.toString()（如 "1.0"）
        // 会覆盖用户正在输入的中间态。文本完全由用户输入驱动，仅初始值取自 value。
        var text by remember(label) {
            mutableStateOf(value?.let { if (isInt) it.toInt().toString() else it.toString() } ?: "")
        }
        val parsed = text.toFloatOrNull()
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                val v = input.toFloatOrNull()
                if (v != null && v in range) {
                    onValueChange(v)
                } else if (input.isBlank()) {
                    onValueChange(null)
                }
            },
            isError = text.isNotBlank() && (parsed == null || parsed !in range),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

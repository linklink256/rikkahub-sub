package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.copyProvider
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput

@Composable
fun ASRProviderConfigure(
    setting: ASRProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        FormItem(
            label = { Text(stringResource(R.string.setting_asr_configure_provider_type)) },
            description = { Text(stringResource(R.string.setting_asr_configure_provider_type_desc)) }
        ) {
            OutlinedTextField(
                value = setting.typeDisplayName(),
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        FormItem(
            label = { Text(stringResource(R.string.setting_asr_configure_name)) },
            description = { Text(stringResource(R.string.setting_asr_configure_name_desc)) }
        ) {
            OutlinedTextField(
                value = setting.name,
                onValueChange = { onValueChange(setting.copyProvider(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("OpenAI Realtime") }
            )

        }

        when (setting) {
            is ASRProviderSetting.OpenAIRealtime -> OpenAIRealtimeASRConfiguration(setting, onValueChange)
            is ASRProviderSetting.DashScope -> DashScopeASRConfiguration(setting, onValueChange)
            is ASRProviderSetting.Volcengine -> VolcengineASRConfiguration(setting, onValueChange)
            is ASRProviderSetting.MiMo -> MiMoASRConfiguration(setting, onValueChange)
            is ASRProviderSetting.Step -> StepASRConfiguration(setting, onValueChange)
        }
    }
}

/**
 * ASR 配置表单通用文本字段: FormItem(label + description) 包裹 OutlinedTextField。
 *
 * label / description / placeholder 均为已解析字符串, 由调用方传入, 以保留各 provider
 * 间描述与占位符的差异。替代 5 个子配置函数里重复的 FormItem + OutlinedTextField 块。
 */
@Composable
fun AsrTextField(
    label: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    minLines: Int = 1
) {
    FormItem(
        label = { Text(label) },
        description = { Text(description) }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            minLines = minLines,
            placeholder = if (placeholder != null) {
                { Text(placeholder) }
            } else {
                null
            }
        )
    }
}

/**
 * ASR 配置表单通用数字字段: FormItem(label + description) 包裹 OutlinedNumberInput。
 *
 * [range] 为空时不做范围校验; 否则仅当值落在范围内时才回调, 保留原有的字段校验逻辑
 * (UI 层不报错, 仅静默丢弃越界值)。numberLabel 为 OutlinedNumberInput 内部浮动标签。
 */
@Composable
fun <T> AsrNumberField(
    label: String,
    description: String,
    value: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedRange<T>? = null,
    numberLabel: String = ""
) where T : Number, T : Comparable<T> {
    FormItem(
        label = { Text(label) },
        description = { Text(description) }
    ) {
        OutlinedNumberInput(
            value = value,
            onValueChange = { v ->
                if (range == null || v in range) {
                    onValueChange(v)
                }
            },
            modifier = modifier.fillMaxWidth(),
            label = numberLabel
        )
    }
}

@Composable
private fun OpenAIRealtimeASRConfiguration(
    setting: ASRProviderSetting.OpenAIRealtime,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_api_key),
            description = stringResource(R.string.setting_asr_configure_openai_api_key_desc),
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            placeholder = "sk-..."
        )

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_websocket_url),
            description = stringResource(R.string.setting_asr_configure_openai_websocket_desc),
            value = setting.websocketUrl,
            onValueChange = { onValueChange(setting.copy(websocketUrl = it)) },
            placeholder = "wss://api.openai.com/v1/realtime?intent=transcription"
        )

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_model),
            description = stringResource(R.string.setting_asr_configure_model_desc),
            value = setting.model,
            onValueChange = { onValueChange(setting.copy(model = it)) },
            placeholder = "gpt-4o-transcribe"
        )

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_language),
            description = stringResource(R.string.setting_asr_configure_language_iso_desc),
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            placeholder = "auto"
        )

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_prompt),
            description = stringResource(R.string.setting_asr_configure_prompt_desc),
            value = setting.prompt,
            onValueChange = { onValueChange(setting.copy(prompt = it)) },
            placeholder = "Optional",
            minLines = 2
        )

        AsrNumberField(
            label = stringResource(R.string.setting_asr_configure_vad_threshold),
            description = stringResource(R.string.setting_asr_configure_vad_desc),
            value = setting.vadThreshold,
            onValueChange = { onValueChange(setting.copy(vadThreshold = it)) },
            range = 0.0f..1.0f,
            numberLabel = "VAD Threshold"
        )

        AsrNumberField(
            label = stringResource(R.string.setting_asr_configure_prefix_padding),
            description = stringResource(R.string.setting_asr_configure_prefix_padding_desc),
            value = setting.prefixPaddingMs,
            onValueChange = { onValueChange(setting.copy(prefixPaddingMs = it)) },
            range = 0..2000,
            numberLabel = "Prefix Padding"
        )

        AsrNumberField(
            label = stringResource(R.string.setting_asr_configure_silence_duration),
            description = stringResource(R.string.setting_asr_configure_silence_duration_desc),
            value = setting.silenceDurationMs,
            onValueChange = { onValueChange(setting.copy(silenceDurationMs = it)) },
            range = 100..5000,
            numberLabel = "Silence Duration"
        )
    }
}

@Composable
private fun DashScopeASRConfiguration(
    setting: ASRProviderSetting.DashScope,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_api_key),
            description = stringResource(R.string.setting_asr_configure_dashscope_api_key_desc),
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            placeholder = "sk-..."
        )

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_websocket_url),
            description = stringResource(R.string.setting_asr_configure_dashscope_websocket_desc),
            value = setting.websocketUrl,
            onValueChange = { onValueChange(setting.copy(websocketUrl = it)) },
            placeholder = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
        )

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_model),
            description = stringResource(R.string.setting_asr_configure_model_desc),
            value = setting.model,
            onValueChange = { onValueChange(setting.copy(model = it)) },
            placeholder = "qwen3-asr-flash-realtime-2026-02-10"
        )

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_language),
            description = stringResource(R.string.setting_asr_configure_language_iso_desc),
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            placeholder = "zh"
        )

        AsrNumberField(
            label = stringResource(R.string.setting_asr_configure_vad_threshold),
            description = stringResource(R.string.setting_asr_configure_dashscope_vad_desc),
            value = setting.vadThreshold,
            onValueChange = { onValueChange(setting.copy(vadThreshold = it)) },
            range = 0.0f..1.0f,
            numberLabel = "VAD Threshold"
        )

        AsrNumberField(
            label = stringResource(R.string.setting_asr_configure_silence_duration),
            description = stringResource(R.string.setting_asr_configure_silence_duration_desc),
            value = setting.silenceDurationMs,
            onValueChange = { onValueChange(setting.copy(silenceDurationMs = it)) },
            range = 100..5000,
            numberLabel = "Silence Duration"
        )
    }
}

@Composable
private fun VolcengineASRConfiguration(
    setting: ASRProviderSetting.Volcengine,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_api_key),
            description = stringResource(R.string.setting_asr_configure_volcengine_api_key_desc),
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            placeholder = "your-api-key"
        )

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_websocket_url),
            description = stringResource(R.string.setting_asr_configure_volcengine_websocket_desc),
            value = setting.websocketUrl,
            onValueChange = { onValueChange(setting.copy(websocketUrl = it)) },
            placeholder = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel"
        )

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_resource_id),
            description = stringResource(R.string.setting_asr_configure_resource_id_desc),
            value = setting.resourceId,
            onValueChange = { onValueChange(setting.copy(resourceId = it)) },
            placeholder = "volc.bigasr.sauc.duration"
        )

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_language),
            description = stringResource(R.string.setting_asr_configure_language_code_desc),
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            placeholder = "auto"
        )
    }
}

@Composable
private fun MiMoASRConfiguration(
    setting: ASRProviderSetting.MiMo,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_api_key),
            description = stringResource(R.string.setting_asr_configure_mimo_api_key_desc),
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            placeholder = "sk-... or tp-..."
        )

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_base_url),
            description = stringResource(R.string.setting_asr_configure_mimo_base_url_desc),
            value = setting.baseUrl,
            onValueChange = { onValueChange(setting.copy(baseUrl = it)) },
            placeholder = "https://api.xiaomimimo.com/v1"
        )

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_model),
            description = stringResource(R.string.setting_asr_configure_mimo_model_desc),
            value = setting.model,
            onValueChange = { onValueChange(setting.copy(model = it)) },
            placeholder = "mimo-v2.5-asr"
        )

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_language),
            description = stringResource(R.string.setting_asr_configure_mimo_language_desc),
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            placeholder = "auto"
        )

        AsrNumberField(
            label = stringResource(R.string.setting_asr_configure_sample_rate),
            description = stringResource(R.string.setting_asr_configure_mimo_sample_rate_desc),
            value = setting.sampleRate,
            onValueChange = { onValueChange(setting.copy(sampleRate = it)) },
            range = 8000..48000,
            numberLabel = "Sample Rate"
        )

        AsrNumberField(
            label = stringResource(R.string.setting_asr_configure_segment_duration),
            description = stringResource(R.string.setting_asr_configure_mimo_segment_desc),
            value = setting.segmentDurationSec,
            onValueChange = { onValueChange(setting.copy(segmentDurationSec = it)) },
            range = 0..300,
            numberLabel = "Segment Duration (s)"
        )
    }
}

@Composable
private fun StepASRConfiguration(
    setting: ASRProviderSetting.Step,
    onValueChange: (ASRProviderSetting) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_api_key),
            description = stringResource(R.string.setting_asr_configure_step_api_key_desc),
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            placeholder = "your-stepfun-api-key"
        )

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_base_url),
            description = stringResource(R.string.setting_asr_configure_step_base_url_desc),
            value = setting.baseUrl,
            onValueChange = { onValueChange(setting.copy(baseUrl = it)) },
            placeholder = "https://api.stepfun.com"
        )

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_model),
            description = stringResource(R.string.setting_asr_configure_step_model_desc),
            value = setting.model,
            onValueChange = { onValueChange(setting.copy(model = it)) },
            placeholder = "stepaudio-2.5-asr"
        )

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_language),
            description = stringResource(R.string.setting_asr_configure_step_language_desc),
            value = setting.language,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            placeholder = "auto"
        )

        AsrNumberField(
            label = stringResource(R.string.setting_asr_configure_sample_rate),
            description = stringResource(R.string.setting_asr_configure_step_sample_rate_desc),
            value = setting.sampleRate,
            onValueChange = { onValueChange(setting.copy(sampleRate = it)) },
            range = 8000..48000,
            numberLabel = "Sample Rate"
        )

        AsrNumberField(
            label = stringResource(R.string.setting_asr_configure_segment_duration),
            description = stringResource(R.string.setting_asr_configure_step_segment_desc),
            value = setting.segmentDurationSec,
            onValueChange = { onValueChange(setting.copy(segmentDurationSec = it)) },
            range = 0..300,
            numberLabel = "Segment Duration (s)"
        )

        FormItem(
            label = { Text(stringResource(R.string.setting_asr_configure_step_itn)) },
            description = { Text(stringResource(R.string.setting_asr_configure_step_itn_desc)) }
        ) {
            Switch(
                checked = setting.enableItn,
                onCheckedChange = { onValueChange(setting.copy(enableItn = it)) }
            )
        }

        FormItem(
            label = { Text(stringResource(R.string.setting_asr_configure_step_timestamp)) },
            description = { Text(stringResource(R.string.setting_asr_configure_step_timestamp_desc)) }
        ) {
            Switch(
                checked = setting.enableTimestamp,
                onCheckedChange = { onValueChange(setting.copy(enableTimestamp = it)) }
            )
        }

        AsrTextField(
            label = stringResource(R.string.setting_asr_configure_step_hotwords),
            description = stringResource(R.string.setting_asr_configure_step_hotwords_desc),
            value = setting.hotwords.joinToString(","),
            onValueChange = { text ->
                val list = text.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                onValueChange(setting.copy(hotwords = list))
            },
            placeholder = "热词1, 热词2, 热词3"
        )
    }
}

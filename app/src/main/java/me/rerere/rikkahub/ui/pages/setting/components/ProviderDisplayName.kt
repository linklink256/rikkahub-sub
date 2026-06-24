package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.R
import me.rerere.tts.provider.TTSProviderSetting

/**
 * 统一的 TTS Provider 类型显示名。
 *
 * OpenAI / Gemini / System 有 i18n 字符串资源, 用 stringResource; 其余为品牌名, 暂无
 * 资源, 硬编码 (按约定不新增 string resource)。替代原先散落在 SettingSpeechPage 与
 * TTSProviderConfigure 里的 when(provider/setting) 分支。
 */
@Composable
fun TTSProviderSetting.typeDisplayName(): String = when (this) {
    is TTSProviderSetting.OpenAI -> stringResource(R.string.setting_tts_page_provider_openai)
    is TTSProviderSetting.Gemini -> stringResource(R.string.setting_tts_page_provider_gemini)
    is TTSProviderSetting.SystemTTS -> stringResource(R.string.setting_tts_page_provider_system)
    is TTSProviderSetting.MiniMax -> "MiniMax"
    is TTSProviderSetting.Qwen -> "Qwen"
    is TTSProviderSetting.Groq -> "Groq"
    is TTSProviderSetting.XAI -> "xAI"
    is TTSProviderSetting.MiMo -> "MiMo"
    is TTSProviderSetting.ElevenLabs -> "ElevenLabs"
}

/**
 * 统一的 ASR Provider 类型显示名。均为品牌名, 暂无 i18n 资源, 硬编码。
 *
 * 替代原先散落在 SettingSpeechPage 与 ASRProviderConfigure 里的 when(provider/setting) 分支。
 */
@Composable
fun ASRProviderSetting.typeDisplayName(): String = when (this) {
    is ASRProviderSetting.OpenAIRealtime -> "OpenAI Realtime"
    is ASRProviderSetting.DashScope -> "DashScope"
    is ASRProviderSetting.Volcengine -> "Volcengine"
    is ASRProviderSetting.MiMo -> "MiMo"
    is ASRProviderSetting.Step -> "Step"
}

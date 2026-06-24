package me.rerere.tts.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
sealed class TTSProviderSetting {
    abstract val id: Uuid
    abstract val name: String

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var name: String = "OpenAI TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.openai.com/v1",
        val model: String = "gpt-4o-mini-tts",
        val voice: String = "alloy"
    ) : TTSProviderSetting() {
    }

    @Serializable
    @SerialName("gemini")
    data class Gemini(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Gemini TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        val model: String = "gemini-2.5-flash-preview-tts",
        val voiceName: String = "Kore"
    ) : TTSProviderSetting() {
    }

    @Serializable
    @SerialName("system")
    data class SystemTTS(
        override var id: Uuid = Uuid.random(),
        override var name: String = "System TTS",
        val speechRate: Float = 1.0f,
        val pitch: Float = 1.0f,
    ) : TTSProviderSetting() {
    }

    @Serializable
    @SerialName("minimax")
    data class MiniMax(
        override var id: Uuid = Uuid.random(),
        override var name: String = "MiniMax TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.minimaxi.com/v1",
        val model: String = "speech-2.6-turbo",
        val voiceId: String = "female-shaonv",
        val emotion: String = "auto",
        val speed: Float = 1.0f
    ) : TTSProviderSetting() {
    }

    @Serializable
    @SerialName("qwen")
    data class Qwen(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Qwen TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://dashscope.aliyuncs.com/api/v1",
        val model: String = "qwen3-tts-flash",
        val voice: String = "Cherry",
        val languageType: String = "Auto"
    ) : TTSProviderSetting() {
    }

    @Serializable
    @SerialName("groq")
    data class Groq(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Groq TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.groq.com/openai/v1",
        val model: String = "canopylabs/orpheus-v1-english",
        val voice: String = "austin"
    ) : TTSProviderSetting() {
    }

    @Serializable
    @SerialName("xai")
    data class XAI(
        override var id: Uuid = Uuid.random(),
        override var name: String = "xAI TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.x.ai/v1",
        val voiceId: String = "eve",
        val language: String = "auto"
    ) : TTSProviderSetting() {
    }

    @Serializable
    @SerialName("mimo")
    // 默认值仅用于快捷起步 可在设置页任意修改
    data class MiMo(
        override var id: Uuid = Uuid.random(),
        override var name: String = "MiMo TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.xiaomimimo.com/v1",
        val model: String = "mimo-v2-tts",
        val voice: String = "mimo_default"
    ) : TTSProviderSetting() {
    }


    @Serializable
    @SerialName("elevenlabs")
    data class ElevenLabs(
        override var id: Uuid = Uuid.random(),
        override var name: String = "ElevenLabs TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.elevenlabs.io",
        val model: String = "eleven_multilingual_v2",
        val voiceId: String = "JBFqnCBsd6RMkjVDRZzb",
        val stability: Float = 0.5f,
        val similarityBoost: Float = 0.75f,
    ) : TTSProviderSetting()

    /**
     * 阶跃星辰 Step TTS (step-tts-mini / step-tts-vivid / stepaudio-2.5-tts)。
     *
     * 与 Step ASR 共用同一个 baseUrl 与鉴权方式 (Authorization: Bearer sk-xxx),
     * 走 OpenAI 兼容的 [POST /v1/audio/speech] 非流式接口, 服务端一次性返回完整音频
     * 二进制 (默认 mp3, 也可选 wav/pcm/opus/flac)。客户端把整段音频包成一个 AudioChunk
     * 发出, 由 TtsSynthesizer 统一收集后交给播放器。
     *
     * 仅 stepaudio-2.5-tts 模型支持 [instruction] 字段 (全局语境, ≤200 字符), 其它模型
     * (step-tts-mini / step-tts-vivid / step-tts-2) 会忽略该字段, 留空时不下发。
     *
     * 官方文档:
     * - 模型总览: https://platform.stepfun.com/docs/zh/guides/models/stepaudio-2.5-tts
     * - 开发指南: https://platform.stepfun.com/docs/zh/guides/developer/tts
     */
    @Serializable
    @SerialName("step")
    data class Step(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Step TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.stepfun.com",
        val model: String = "step-tts-mini",
        val voice: String = "elegantgentle-female",
        val responseFormat: String = "mp3",
        val speed: Float = 1.0f,
        val volume: Float = 1.0f,
        val sampleRate: Int = 24000,
        val instruction: String = "",
    ) : TTSProviderSetting()
    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Gemini::class,
                SystemTTS::class,
                MiniMax::class,
                Qwen::class,
                Groq::class,
                XAI::class,
                MiMo::class,
                ElevenLabs::class,
                Step::class,
            )
        }
    }
}

// ponytail: single extension replaces 8 identical copyProvider overrides
fun TTSProviderSetting.copyProvider(
    id: Uuid = this.id,
    name: String = this.name,
): TTSProviderSetting = when (this) {
    is TTSProviderSetting.OpenAI -> copy(id = id, name = name)
    is TTSProviderSetting.Gemini -> copy(id = id, name = name)
    is TTSProviderSetting.SystemTTS -> copy(id = id, name = name)
    is TTSProviderSetting.MiniMax -> copy(id = id, name = name)
    is TTSProviderSetting.Qwen -> copy(id = id, name = name)
    is TTSProviderSetting.Groq -> copy(id = id, name = name)
    is TTSProviderSetting.XAI -> copy(id = id, name = name)
    is TTSProviderSetting.MiMo -> copy(id = id, name = name)
    is TTSProviderSetting.ElevenLabs -> copy(id = id, name = name)
    is TTSProviderSetting.Step -> copy(id = id, name = name)
}

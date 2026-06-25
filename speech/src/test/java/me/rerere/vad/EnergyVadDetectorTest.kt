package me.rerere.vad

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 纯逻辑单测：能量 VAD 的 RMS 计算。
 *
 * barge-in 阈值调优（3000 -> 6000）是否合理，取决于 RMS 的能量刻度是否正确。
 * 这里固定该刻度：恒定幅度信号的 RMS == 该幅度值，因此阈值 6000 对应
 * 一个幅度恒为 6000 的 PCM 信号（约占 16-bit 满量程 32767 的 18%）。
 *
 * 注：运行时 AEC 检测（[EnergyVadDetector.hasAec]）依赖 Android AudioRecord 状态，
 * 无法在纯 JVM 单测中覆盖，需在 instrumented 测试中验证。
 */
class EnergyVadDetectorTest {
    @Test
    fun rms_of_silence_is_zero() {
        val buffer = ShortArray(512) // 全零
        assertEquals(0.0, calculateRms(buffer, buffer.size), 0.01)
    }

    @Test
    fun rms_of_constant_amplitude_equals_that_amplitude() {
        // 恒定幅度信号 RMS == 幅度。这把能量刻度钉死：
        // 阈值 6000 即“幅度恒为 6000 的信号”，作为 barge-in 调参基准。
        val amplitude = 6000
        val buffer = ShortArray(512) { amplitude.toShort() }
        assertEquals(amplitude.toDouble(), calculateRms(buffer, buffer.size), 0.01)
    }

    @Test
    fun rms_of_full_scale_is_max() {
        val buffer = ShortArray(512) { 32767 }
        assertEquals(32767.0, calculateRms(buffer, buffer.size), 0.5)
    }

    @Test
    fun rms_only_considers_first_count_samples() {
        // 前 4 个样本幅度 6000，其余为 0；count=4 时 RMS 应为 6000。
        val buffer = ShortArray(512) { 6000 }
        for (i in 4 until buffer.size) buffer[i] = 0
        assertEquals(6000.0, calculateRms(buffer, 4), 0.01)
    }

    // ---- 有效阈值 (AEC 自适应) ----

    @Test
    fun effective_threshold_tripled_without_aec() {
        // 无 AEC 时阈值提高 3 倍: 6000 -> 18000
        // 避免 TTS 扬声器回声被误判为用户说话
        assertEquals(18000.0, computeEffectiveThreshold(6000.0, hasAec = false), 0.01)
    }

    @Test
    fun effective_threshold_unchanged_with_aec() {
        // 有 AEC (VOICE_COMMUNICATION 源) 时阈值不变
        assertEquals(6000.0, computeEffectiveThreshold(6000.0, hasAec = true), 0.01)
    }

    // ---- 校准后最终阈值 (噪声基线自适应) ----

    @Test
    fun calibrated_threshold_uses_effective_when_noise_floor_low() {
        // 噪声基线远低于有效阈值时, 最终阈值 = effectiveThreshold
        // noiseFloor=1000 → 2500 < 18000 → 18000
        assertEquals(18000.0, computeCalibratedThreshold(18000.0, noiseFloor = 1000.0), 0.01)
    }

    @Test
    fun calibrated_threshold_uses_noise_floor_when_high() {
        // 噪声基线高时, 最终阈值 = noiseFloor * 2.5
        // noiseFloor=10000 → 25000 > 18000 → 25000
        assertEquals(25000.0, computeCalibratedThreshold(18000.0, noiseFloor = 10000.0), 0.01)
    }

    @Test
    fun calibrated_threshold_takes_max_of_effective_and_noise() {
        // 边界: noiseFloor * 2.5 恰好等于 effectiveThreshold
        // effectiveThreshold=18000, noiseFloor=7200 → 18000 == 18000 → 18000
        assertEquals(18000.0, computeCalibratedThreshold(18000.0, noiseFloor = 7200.0), 0.01)
    }

    @Test
    fun calibrated_threshold_with_zero_noise_floor() {
        // 完全静音校准: noiseFloor=0 → 0 < effectiveThreshold → effectiveThreshold
        assertEquals(18000.0, computeCalibratedThreshold(18000.0, noiseFloor = 0.0), 0.01)
    }
}

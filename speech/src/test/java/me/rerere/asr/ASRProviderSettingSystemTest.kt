package me.rerere.asr

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ASRProviderSettingSystemTest {
    @Test
    fun system_defaults_are_expected() {
        val setting = ASRProviderSetting.SystemASR()

        assertEquals("System ASR", setting.name)
        // System ASR does not require an API key, so it has no apiKey field
    }

    @Test
    fun system_is_registered_in_provider_types() {
        assertTrue(ASRProviderSetting.Types.contains(ASRProviderSetting.SystemASR::class))
    }

    @Test
    fun system_copy_provider_preserves_identity() {
        val original = ASRProviderSetting.SystemASR(name = "My System ASR")
        val copied = original.copyProvider(id = original.id, name = "renamed")

        assertTrue(copied is ASRProviderSetting.SystemASR)
        val system = copied as ASRProviderSetting.SystemASR
        assertEquals("renamed", system.name)
        assertEquals(original.id, system.id)
    }

    @Test
    fun system_serializes_with_system_discriminator() {
        val json = Json { encodeDefaults = true }
        val setting = ASRProviderSetting.SystemASR(name = "System ASR")
        val encoded = json.encodeToString(ASRProviderSetting.serializer(), setting)

        // @SerialName("system") must produce a "type":"system" discriminator
        assertTrue("encoded json should contain type=system: $encoded", encoded.contains("\"type\":\"system\""))
    }

    @Test
    fun system_round_trips_through_serialization() {
        val json = Json { ignoreUnknownKeys = true }
        val original = ASRProviderSetting.SystemASR(name = "Offline ASR")
        val encoded = json.encodeToString(ASRProviderSetting.serializer(), original)
        val decoded = json.decodeFromString(ASRProviderSetting.serializer(), encoded)

        assertTrue(decoded is ASRProviderSetting.SystemASR)
        val system = decoded as ASRProviderSetting.SystemASR
        assertEquals(original.id, system.id)
        assertEquals("Offline ASR", system.name)
    }

    @Test
    fun system_can_be_decoded_from_type_only_payload() {
        // Minimal payload with just the discriminator should yield a SystemASR
        // (id/name have defaults)
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(ASRProviderSetting.serializer(), """{"type":"system"}""")

        assertTrue(decoded is ASRProviderSetting.SystemASR)
        assertFalse((decoded as ASRProviderSetting.SystemASR).name.isEmpty())
    }
}

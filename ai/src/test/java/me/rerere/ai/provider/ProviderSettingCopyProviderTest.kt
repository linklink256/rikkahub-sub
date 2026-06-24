package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderSettingCopyProviderTest {
    @Test
    fun openai_copy_provider_preserves_extra_fields() {
        val original = ProviderSetting.OpenAI(
            apiKey = "sk-openai",
            baseUrl = "https://example.com/v1",
            chatCompletionsPath = "/custom",
            useResponseApi = true,
            includeHistoryReasoning = false,
        )
        val copied = original.copyProvider(name = "renamed")

        assertTrue(copied is ProviderSetting.OpenAI)
        val openai = copied as ProviderSetting.OpenAI
        assertEquals("renamed", openai.name)
        assertEquals("sk-openai", openai.apiKey)
        assertEquals("https://example.com/v1", openai.baseUrl)
        assertEquals("/custom", openai.chatCompletionsPath)
        assertTrue(openai.useResponseApi)
        assertEquals(false, openai.includeHistoryReasoning)
    }

    @Test
    fun google_copy_provider_preserves_extra_fields() {
        val original = ProviderSetting.Google(
            apiKey = "sk-google",
            baseUrl = "https://google.example/v1beta",
            vertexAI = true,
            useServiceAccount = true,
            privateKey = "pk",
            serviceAccountEmail = "sa@example.com",
            location = "eu-west1",
            projectId = "proj-1",
        )
        val copied = original.copyProvider(name = "renamed")

        assertTrue(copied is ProviderSetting.Google)
        val google = copied as ProviderSetting.Google
        assertEquals("renamed", google.name)
        assertEquals("sk-google", google.apiKey)
        assertEquals("https://google.example/v1beta", google.baseUrl)
        assertTrue(google.vertexAI)
        assertTrue(google.useServiceAccount)
        assertEquals("pk", google.privateKey)
        assertEquals("sa@example.com", google.serviceAccountEmail)
        assertEquals("eu-west1", google.location)
        assertEquals("proj-1", google.projectId)
    }

    @Test
    fun claude_copy_provider_preserves_extra_fields() {
        val original = ProviderSetting.Claude(
            apiKey = "sk-claude",
            baseUrl = "https://claude.example/v1",
            promptCaching = true,
            promptCacheTtl = ClaudePromptCacheTtl.ONE_HOUR,
        )
        val copied = original.copyProvider(name = "renamed")

        assertTrue(copied is ProviderSetting.Claude)
        val claude = copied as ProviderSetting.Claude
        assertEquals("renamed", claude.name)
        assertEquals("sk-claude", claude.apiKey)
        assertEquals("https://claude.example/v1", claude.baseUrl)
        assertTrue(claude.promptCaching)
        assertEquals(ClaudePromptCacheTtl.ONE_HOUR, claude.promptCacheTtl)
    }

    @Test
    fun copy_provider_with_defaults_preserves_shared_fields() {
        val id = Uuid.random()
        val models = listOf(Model(modelId = "m1"), Model(modelId = "m2"))
        val balance = BalanceOption(enabled = true, apiPath = "/x", resultPath = "data.y")
        val original = ProviderSetting.OpenAI(
            id = id,
            enabled = false,
            name = "orig",
            models = models,
            balanceOption = balance,
            builtIn = true,
        )
        val copied = original.copyProvider()

        assertEquals(id, copied.id)
        assertEquals(false, copied.enabled)
        assertEquals("orig", copied.name)
        assertEquals(models, copied.models)
        assertEquals(balance, copied.balanceOption)
        assertEquals(true, copied.builtIn)
    }

    @Test
    fun copy_provider_positional_first_arg_is_id() {
        // Real call site: it.copyProvider(Uuid.random()) -- positional, must map to `id`.
        val original = ProviderSetting.OpenAI(name = "orig")
        val newId = Uuid.random()
        val copied = original.copyProvider(newId)

        assertEquals(newId, copied.id)
        assertEquals("orig", copied.name)
    }

    @Test
    fun copy_provider_partial_named_args_keep_rest() {
        val original = ProviderSetting.Claude(
            apiKey = "sk-claude",
            promptCaching = true,
            promptCacheTtl = ClaudePromptCacheTtl.ONE_HOUR,
        )
        val copied = original.copyProvider(
            builtIn = true,
            description = {},
            shortDescription = {},
        )

        assertEquals(true, copied.builtIn)
        // extra fields untouched
        val claude = copied as ProviderSetting.Claude
        assertEquals("sk-claude", claude.apiKey)
        assertTrue(claude.promptCaching)
        assertEquals(ClaudePromptCacheTtl.ONE_HOUR, claude.promptCacheTtl)
    }
}

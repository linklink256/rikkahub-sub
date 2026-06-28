package me.rerere.rikkahub.ui.pages.setting

import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.DEFAULT_SYSTEM_TTS_ID
import me.rerere.rikkahub.data.datastore.DEFAULT_TTS_PROVIDER_IDS
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.base.BaseSettingsVM
import me.rerere.rikkahub.utils.move
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting

class SettingVM(
    settingsStore: SettingsStore,
    private val mcpManager: McpManager,
) : BaseSettingsVM(settingsStore) {

    // region Providers
    fun addProvider(provider: ProviderSetting) {
        update { settings ->
            settings.copy(providers = listOf(provider) + settings.providers)
        }
    }

    fun deleteProvider(provider: ProviderSetting) {
        update { settings ->
            settings.copy(providers = settings.providers - provider)
        }
    }

    fun reorderProviders(from: Int, to: Int) {
        update { settings ->
            settings.copy(providers = settings.providers.move(from, to))
        }
    }
    // endregion

    // region Search services
    fun addSearchService(options: SearchServiceOptions) {
        update { settings ->
            settings.copy(searchServices = listOf(options) + settings.searchServices)
        }
    }

    fun updateSearchService(updated: SearchServiceOptions) {
        update { settings ->
            settings.copy(
                searchServices = settings.searchServices.map {
                    if (it.id == updated.id) updated else it
                }
            )
        }
    }

    fun deleteSearchService(service: SearchServiceOptions) {
        update { settings ->
            if (settings.searchServices.size > 1) {
                settings.copy(searchServices = settings.searchServices.filter { it.id != service.id })
            } else {
                settings
            }
        }
    }

    fun reorderSearchServices(from: Int, to: Int) {
        val services = settings.value.searchServices
        if (from !in services.indices || to !in services.indices) return
        update { settings ->
            settings.copy(searchServices = settings.searchServices.move(from, to))
        }
    }
    // endregion

    // region TTS providers
    fun addTtsProvider(provider: TTSProviderSetting) {
        update { settings ->
            settings.copy(ttsProviders = listOf(provider) + settings.ttsProviders)
        }
    }

    fun updateTtsProvider(updated: TTSProviderSetting) {
        update { settings ->
            settings.copy(
                ttsProviders = settings.ttsProviders.map {
                    if (it.id == updated.id) updated else it
                }
            )
        }
    }

    fun deleteTtsProvider(provider: TTSProviderSetting) {
        update { settings ->
            val newProviders = settings.ttsProviders - provider
            val newSelectedId =
                if (settings.selectedTTSProviderId == provider.id) {
                    DEFAULT_SYSTEM_TTS_ID
                } else {
                    settings.selectedTTSProviderId
                }
            val newHidden = if (provider.id in DEFAULT_TTS_PROVIDER_IDS) {
                settings.hiddenTtsProviderIds + provider.id
            } else {
                settings.hiddenTtsProviderIds
            }
            settings.copy(
                ttsProviders = newProviders,
                selectedTTSProviderId = newSelectedId,
                hiddenTtsProviderIds = newHidden,
            )
        }
    }

    fun reorderTtsProviders(from: Int, to: Int) {
        update { settings ->
            settings.copy(ttsProviders = settings.ttsProviders.move(from, to))
        }
    }

    fun selectTtsProvider(provider: TTSProviderSetting) {
        update { settings ->
            settings.copy(selectedTTSProviderId = provider.id)
        }
    }
    // endregion

    // region ASR providers
    fun addAsrProvider(provider: ASRProviderSetting) {
        update { settings ->
            settings.copy(
                asrProviders = listOf(provider) + settings.asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId ?: provider.id,
            )
        }
    }

    fun updateAsrProvider(updated: ASRProviderSetting) {
        update { settings ->
            settings.copy(
                asrProviders = settings.asrProviders.map {
                    if (it.id == updated.id) updated else it
                }
            )
        }
    }

    fun deleteAsrProvider(provider: ASRProviderSetting) {
        update { settings ->
            val newProviders = settings.asrProviders - provider
            val newSelectedId =
                if (settings.selectedASRProviderId == provider.id) {
                    newProviders.firstOrNull()?.id
                } else {
                    settings.selectedASRProviderId
                }
            settings.copy(
                asrProviders = newProviders,
                selectedASRProviderId = newSelectedId,
            )
        }
    }

    fun reorderAsrProviders(from: Int, to: Int) {
        update { settings ->
            settings.copy(asrProviders = settings.asrProviders.move(from, to))
        }
    }

    fun selectAsrProvider(provider: ASRProviderSetting) {
        update { settings ->
            settings.copy(selectedASRProviderId = provider.id)
        }
    }
    // endregion

    // region MCP servers
    fun addMcpServer(config: McpServerConfig) {
        update { settings ->
            settings.copy(mcpServers = settings.mcpServers + config)
        }
    }

    fun updateMcpServer(updated: McpServerConfig) {
        update { settings ->
            settings.copy(
                mcpServers = settings.mcpServers.map {
                    if (it.id == updated.id) updated else it
                }
            )
        }
    }

    fun deleteMcpServer(config: McpServerConfig) {
        update { settings ->
            settings.copy(mcpServers = settings.mcpServers.filter { it.id != config.id })
        }
    }

    fun reorderMcpServers(from: Int, to: Int) {
        update { settings ->
            settings.copy(mcpServers = settings.mcpServers.move(from, to))
        }
    }

    fun importMcpServers(newConfigs: List<McpServerConfig>) {
        update { settings ->
            val existingNames = settings.mcpServers.map { it.commonOptions.name }.toSet()
            val toAdd = newConfigs.filter {
                it.commonOptions.name.isNotBlank() && it.commonOptions.name !in existingNames
            }
            settings.copy(mcpServers = settings.mcpServers + toAdd)
        }
    }
    // endregion
}

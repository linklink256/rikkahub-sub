package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore

class PromptVM(
    private val settingsStore: SettingsStore
) : ViewModel() {
    // settingsStore.settingsFlow is already a MutableStateFlow (hot) with the
    // real current value. Expose it directly instead of wrapping with stateIn
    // + Settings.dummy() to avoid a flash of dummy data on screen entry.
    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }
}

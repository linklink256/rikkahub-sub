package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.ui.base.BaseSettingsVM
import me.rerere.rikkahub.utils.move

class PromptVM(
    settingsStore: SettingsStore,
) : BaseSettingsVM(settingsStore) {

    // region Lorebooks
    fun addOrUpdateLorebook(edited: Lorebook) {
        update { settings ->
            val lorebooks = settings.lorebooks
            val index = lorebooks.indexOfFirst { it.id == edited.id }
            val newLorebooks = if (index >= 0) {
                lorebooks.toMutableList().apply { set(index, edited) }
            } else {
                lorebooks + edited
            }
            settings.copy(lorebooks = newLorebooks)
        }
    }

    fun deleteLorebook(book: Lorebook) {
        update { settings ->
            settings.copy(lorebooks = settings.lorebooks - book)
        }
    }

    fun reorderLorebooks(from: Int, to: Int) {
        update { settings ->
            settings.copy(lorebooks = settings.lorebooks.move(from, to))
        }
    }
    // endregion

    // region Mode injections
    fun addOrUpdateModeInjection(edited: PromptInjection.ModeInjection) {
        update { settings ->
            val modeInjections = settings.modeInjections
            val index = modeInjections.indexOfFirst { it.id == edited.id }
            val newInjections = if (index >= 0) {
                modeInjections.toMutableList().apply { set(index, edited) }
            } else {
                modeInjections + edited
            }
            settings.copy(modeInjections = newInjections)
        }
    }

    fun deleteModeInjection(injection: PromptInjection.ModeInjection) {
        update { settings ->
            settings.copy(modeInjections = settings.modeInjections - injection)
        }
    }

    fun reorderModeInjections(from: Int, to: Int) {
        update { settings ->
            settings.copy(modeInjections = settings.modeInjections.move(from, to))
        }
    }
    // endregion
}

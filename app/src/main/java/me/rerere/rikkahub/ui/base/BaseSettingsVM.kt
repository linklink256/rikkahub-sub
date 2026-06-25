package me.rerere.rikkahub.ui.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * ViewModel 基类, 统一暴露 settings 状态与更新能力。
 *
 * 子类通过构造函数注入 [SettingsStore] (与本项目中其它 ViewModel 一致, 由 Koin 的
 * `viewModelOf(::SubVM)` 解析)。继承本类即可获得:
 * - [settings]: 当前设置状态流
 * - [updateSettings]: 异步更新设置
 * - [update]: 基于当前值变换后更新 (便捷方法)
 *
 * 注意: [settings] 默认直接暴露 `settingsStore.settingsFlow` (一个持有真实当前值的
 * MutableStateFlow 热流), 而非使用 `stateIn` + `Settings.dummy()`, 以避免进入页面时
 * 闪现 dummy 数据。如子类有特殊需求 (例如 [me.rerere.rikkahub.ui.pages.backup.BackupVM]
 * 使用 `stateIn`), 可 override 该属性。
 */
abstract class BaseSettingsVM(
    protected val settingsStore: SettingsStore,
) : ViewModel() {
    open val settings: StateFlow<Settings> = settingsStore.settingsFlow

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    /**
     * 便捷方法: 基于当前 [settings] 值进行变换后更新。
     */
    protected fun update(transform: (Settings) -> Settings) {
        updateSettings(transform(settings.value))
    }
}

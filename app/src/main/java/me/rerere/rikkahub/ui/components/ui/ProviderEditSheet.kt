package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

/**
 * 可复用的"编辑/新增 Provider" BottomSheet 外壳.
 *
 * 提供统一的标题、可滚动配置区、取消/确认按钮布局, 消除各设置页中重复的
 * ModalBottomSheet 样板. 调用方通过 [configure] 注入具体的配置组件.
 *
 * @param title 顶部标题文案.
 * @param confirmText 确认按钮文案 (如 保存/新增).
 * @param setting 初始配置, 内部会维护一份可编辑副本.
 * @param onConfirm 点击确认时回调, 参数为编辑后的配置.
 * @param onDismiss 点击取消或外部dismiss时回调.
 * @param configure 具体配置组件, 接收 (当前配置, 值变更回调, modifier).
 * @param modifier 应用于 ModalBottomSheet 的 modifier.
 * @param sheetHeightFraction 内容区高度占屏幕比例, 默认 0.8f.
 */
@Composable
fun <T> ProviderEditSheet(
    title: String,
    confirmText: String,
    setting: T,
    onConfirm: (T) -> Unit,
    onDismiss: () -> Unit,
    configure: @Composable (T, (T) -> Unit, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    sheetHeightFraction: Float = 0.8f,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    var currentSetting by remember(setting) { mutableStateOf(setting) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .fillMaxHeight(sheetHeightFraction),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )
            configure(
                currentSetting,
                { currentSetting = it },
                Modifier.weight(1f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onClick = { onConfirm(currentSetting) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(confirmText)
                }
            }
        }
    }
}

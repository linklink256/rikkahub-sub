package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * 列表卡片抽象基类 — 封装统一的视觉规格和交互骨架。
 *
 * 子类只需覆盖需要的部分：
 * - [title]：标题（必须）
 * - [Leading]：左侧图标/头像（可选，默认无）
 * - [TitleEnd]：标题右侧附加内容（可选）
 * - [Tags]：标签行（可选）
 * - [Trailing]：右侧操作区（可选）
 * - [containerColor]：卡片背景色（可选，默认 surfaceBright）
 * - [swipeEnabled]：是否允许滑动删除（可选，默认 true）
 * - [onClick]：整卡点击回调（必须）
 * - [onDelete]：滑动删除回调（必须）
 *
 * 用法：
 * ```
 * class ProviderCard(val provider: ProviderSetting) : BaseListCard<ProviderSetting>(provider) {
 *     override val title = provider.name
 *     override val containerColor = if (provider.enabled) surfaceBright else errorContainer
 *     override val swipeEnabled = provider.enabled
 *     override fun onClick() { nav.navigate(...) }
 *     override fun onDelete() { vm.delete(provider) }
 *     @Composable override fun Leading() { AutoAIIcon(provider.name) }
 *     @Composable override fun Tags() { Tag(SUCCESS) { Text("启用") } }
 * }
 * ```
 */
abstract class BaseListCard<T>(
    val data: T,
) {
    abstract val title: String

    open val containerColor: Color
        @Composable get() = CustomColors.cardColorsOnSurfaceContainer.containerColor

    open val swipeEnabled: Boolean = true

    open val hasLeading: Boolean = true
    open val hasTitleEnd: Boolean = false

    abstract fun onClick()
    abstract fun onDelete()

    @Composable
    open fun Leading() {}

    @Composable
    open fun TitleEnd() {}

    @Composable
    open fun Tags() {}

    @Composable
    open fun Trailing() {}

    /**
     * 渲染卡片内容（不含拖拽/滑动容器）。
     * 通常由 [ReorderableSwipeableItem] 调用，也可直接单独使用。
     */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun Content(modifier: Modifier = Modifier) {
        ListCard(
            onClick = { onClick() },
            modifier = modifier,
            leading = if (hasLeading) { { Leading() } } else null,
            title = title,
            titleEnd = if (hasTitleEnd) { { TitleEnd() } } else null,
            tags = { Tags() },
            trailing = { Trailing() },
            containerColor = containerColor,
        )
    }
}

/**
 * 可拖拽排序 + 滑动删除的列表项包装。
 *
 * 封装了 ReorderableItem + SwipeToDeleteContainer + 长按拖拽 + 触觉反馈 + 缩放动画，
 * 子类 [BaseListCard] 只需提供内容，无需重复样板代码。
 *
 * @param card       卡片基类实例
 * @param state      拖拽排序状态（rememberReorderableLazyListState 的返回值）
 * @param key        列表项 key（与 items key 一致）
 * @param modifier   额外修饰符
 * @param dragScale  拖拽时的缩放比例（默认 0.95f）
 */
@Composable
fun <T> ReorderableSwipeableItem(
    card: BaseListCard<T>,
    state: ReorderableLazyListState,
    key: Any,
    modifier: Modifier = Modifier,
    dragScale: Float = 0.95f,
) {
    val haptic = LocalHapticFeedback.current
    ReorderableItem(
        state = state,
        key = key,
    ) { isDragging ->
        SwipeToDeleteContainer(
            onDelete = { card.onDelete() },
            enabled = card.swipeEnabled,
            modifier = modifier
                .then(
                    if (card.swipeEnabled) {
                        Modifier.longPressDraggableHandle(
                            onDragStarted = {
                                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            },
                            onDragStopped = {
                                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            },
                        )
                    } else {
                        Modifier
                    }
                )
                .graphicsLayer {
                    if (isDragging) {
                        scaleX = dragScale
                        scaleY = dragScale
                    }
                },
        ) {
            card.Content()
        }
    }
}

/**
 * 只读列表项包装（有拖拽但无滑动删除）。
 * 用于纯展示 + 拖拽排序的场景。
 */
@Composable
fun <T> ReorderableReadOnlyItem(
    card: BaseListCard<T>,
    state: ReorderableLazyListState,
    key: Any,
    modifier: Modifier = Modifier,
    dragScale: Float = 0.95f,
) {
    val haptic = LocalHapticFeedback.current
    ReorderableItem(
        state = state,
        key = key,
    ) { isDragging ->
        card.Content(
            modifier = modifier
                .longPressDraggableHandle(
                    onDragStarted = {
                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    },
                    onDragStopped = {
                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                    },
                )
                .graphicsLayer {
                    if (isDragging) {
                        scaleX = dragScale
                        scaleY = dragScale
                    }
                },
        )
    }
}

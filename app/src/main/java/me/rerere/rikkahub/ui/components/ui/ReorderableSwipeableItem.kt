package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
// longPressDraggableHandle 是 ReorderableCollectionItemScope 的成员，通过 ReorderableItem lambda 接收者解析

/**
 * 可拖拽排序 + 弹性滑动删除的列表项包装（组合式，非继承）。
 *
 * 封装 [ReorderableItem] + [SwipeToDeleteContainer] + 长按拖拽 + 触觉反馈 + 拖拽缩放，
 * 消除各列表页重复的交互骨架样板代码。[content] 通常是 [ListCard]。
 *
 * @param onDelete      松手越过阈值时回调
 * @param state         rememberReorderableLazyListState 返回的拖拽状态
 * @param key           列表项 key（与 items key 一致）
 * @param modifier      额外修饰符（如 `Modifier.animateItem()`）；fillMaxWidth 由内部处理，无需传
 * @param swipeEnabled  是否允许滑动删除
 * @param dragEnabled   是否允许长按拖拽排序
 * @param dragScale     拖拽时的缩放比例
 * @param content       卡片内容
 */
@Composable
fun LazyItemScope.ReorderableSwipeableItem(
    onDelete: () -> Unit,
    state: ReorderableLazyListState,
    key: Any,
    modifier: Modifier = Modifier,
    swipeEnabled: Boolean = true,
    dragEnabled: Boolean = true,
    dragScale: Float = 0.95f,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    ReorderableItem(
        state = state,
        key = key,
    ) { isDragging ->
        SwipeToDeleteContainer(
            onDelete = onDelete,
            enabled = swipeEnabled,
            modifier = modifier
                .then(
                    if (dragEnabled) {
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
                    },
                )
                .graphicsLayer {
                    if (isDragging) {
                        scaleX = dragScale
                        scaleY = dragScale
                    }
                },
        ) {
            content()
        }
    }
}

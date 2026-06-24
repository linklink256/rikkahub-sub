package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * 统一列表卡片 — 所有列表页面的卡片视觉规格以此为准。
 *
 * 结构：Card > Row(leading 32dp + Column(title + tags) + trailing)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    title: String,
    titleEnd: (@Composable () -> Unit)? = null,
    tags: @Composable RowScope.() -> Unit = {},
    trailing: @Composable () -> Unit = {},
    containerColor: Color = CustomColors.cardColorsOnSurfaceContainer.containerColor,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    leading()
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    titleEnd?.invoke()
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tags()
                }
            }

            trailing()
        }
    }
}

/**
 * 弹性滑动删除容器 — 仅右→左滑，具有弹力手感。
 *
 * 红色预警"粘"在卡片右边缘：卡片左滑时红色从右边缘向右延伸（不受容器宽度限制），
 * 红色右端圆角贴合卡片风格，左端被卡片自然遮挡（无缝粘连）。
 * 删除时卡片和红色一起向左滑出消失。
 *
 * @param onDelete   松手时且滑动超过阈值时回调
 * @param modifier   传入 longPressDraggableHandle 等拖拽修饰符
 * @param enabled    是否允许滑动删除（false 时卡片不可滑动）
 * @param content     卡片内容（通常是 ListCard）
 */
@Composable
fun SwipeToDeleteContainer(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val damping = 0.3f
    val offset = remember { Animatable(0f) }
    var rawDrag by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val thresholdPx = with(density) { maxWidth.toPx() * 0.3f }
        val fullWidthPx = with(density) { maxWidth.toPx() }
        val cornerRadiusPx = with(density) { 12.dp.toPx() }
        val errorColor = MaterialTheme.colorScheme.error
        val onErrorColor = MaterialTheme.colorScheme.onError
        val redProgress = (rawDrag / thresholdPx).coerceIn(0f, 1f)

        // 前景层：红色尾巴 + 卡片内容，整体跟随手指左移
        // 红色用 drawBehind 画在卡片中间向右延伸，不受容器宽度限制
        // 红色右端圆角（贴合卡片风格），左端从卡片中间开始（被卡片右半部分遮挡 → 无缝粘连）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offset.value }
                .drawBehind {
                    val slideDistance = -offset.value
                    if (slideDistance > 0f) {
                        // 红色左端从卡片中间开始
                        val redLeft = fullWidthPx / 2f
                        val redWidth = slideDistance + fullWidthPx / 2f
                        val redHeight = size.height
                        val r = cornerRadiusPx

                        // 构建路径：左端直角，右端圆角
                        val path = Path().apply {
                            moveTo(redLeft, 0f)
                            lineTo(redLeft + redWidth - r, 0f)
                            arcTo(
                                rect = Rect(
                                    left = redLeft + redWidth - r,
                                    top = 0f,
                                    right = redLeft + redWidth,
                                    bottom = r,
                                ),
                                startAngleDegrees = -90f,
                                sweepAngleDegrees = 90f,
                                forceMoveTo = false,
                            )
                            lineTo(redLeft + redWidth, redHeight - r)
                            arcTo(
                                rect = Rect(
                                    left = redLeft + redWidth - r,
                                    top = redHeight - r,
                                    right = redLeft + redWidth,
                                    bottom = redHeight,
                                ),
                                startAngleDegrees = 0f,
                                sweepAngleDegrees = 90f,
                                forceMoveTo = false,
                            )
                            lineTo(redLeft, redHeight)
                            close()
                        }
                        clipPath(path) {
                            drawRect(
                                color = errorColor.copy(alpha = redProgress),
                                topLeft = Offset(redLeft, 0f),
                                size = Size(redWidth, redHeight),
                            )
                        }
                    }
                }
                .then(
                    if (enabled) {
                        Modifier.pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    val reached = rawDrag >= thresholdPx
                                    if (reached) {
                                        scope.launch {
                                            offset.animateTo(
                                                targetValue = -fullWidthPx,
                                                animationSpec = tween(durationMillis = 250),
                                            )
                                            onDelete()
                                        }
                                    } else {
                                        rawDrag = 0f
                                        scope.launch {
                                            offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                        }
                                    }
                                },
                                onDragCancel = {
                                    rawDrag = 0f
                                    scope.launch {
                                        offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                    }
                                },
                            ) { _, dragAmount ->
                                val next = (rawDrag - dragAmount).coerceAtLeast(0f)
                                rawDrag = next
                                val displayed = if (next <= thresholdPx) {
                                    next
                                } else {
                                    thresholdPx + (next - thresholdPx) * damping
                                }
                                scope.launch { offset.snapTo(-displayed) }
                            }
                        }
                    } else {
                        Modifier
                    }
                ),
        ) {
            content()
        }

        // 删除图标：固定在容器右侧（不跟随卡片移动），随红色渐显
        // 放在前景 Box 之后（叠加在上层），用 matchParentSize 匹配前景 Box 的尺寸
        if (rawDrag > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .wrapContentSize(Alignment.CenterEnd)
                    .padding(end = 20.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = null,
                    tint = onErrorColor.copy(alpha = redProgress),
                )
            }
        }
    }
}

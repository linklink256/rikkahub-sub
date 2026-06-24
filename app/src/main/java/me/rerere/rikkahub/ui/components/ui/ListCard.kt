package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
 *
 * @param onClick          整卡点击（进入详情页）
 * @param leading          左侧图标/头像，会被约束为 32dp
 * @param title            标题（titleMedium）
 * @param titleEnd         标题右侧附加内容（如状态圆点），可选
 * @param tags             标签行（FlowRow 内），不需要时传空 lambda
 * @param trailing         右侧操作区（按钮等），可选
 * @param containerColor   卡片背景色，默认 surfaceBright；可传入自定义颜色（如禁用态橘色）
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
    containerColor: androidx.compose.ui.graphics.Color = CustomColors.cardColorsOnSurfaceContainer.containerColor,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // leading — 统一 32dp
            if (leading != null) {
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    leading()
                }
            }

            // 中间内容区
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

            // trailing — 自定义按钮区
            trailing()
        }
    }
}

/**
 * 弹性滑动删除容器 — 仅右→左滑，具有弹力手感。
 *
 * 交互行为：
 * 1. 向左滑动时，背景红色预警随滑动距离渐变加深
 * 2. 达到阈值时，红色达到最大饱和度，不再继续加深
 * 3. 超过阈值后继续滑动会受到阻力（阻尼系数 0.3），产生弹力感
 * 4. 在阻力区域松手 → 删除；未过阈值松手 → 弹性回弹
 * 5. 向右滑始终回弹
 * 6. 删除只在松手时触发
 *
 * @param onDelete   松手时且滑动超过阈值时回调
 * @param modifier   传入 longPressDraggableHandle 等拖拽修饰符
 * @param enabled    是否允许滑动删除（false 时卡片不可滑动，纯展示）
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

    // 阻尼系数：超过阈值后每像素手指只移动 0.3 像素 → 阻力感
    val damping = 0.3f

    // 阻尼回弹动画
    val offset = remember { Animatable(0f) }
    // 累计原始左滑量（正值），用于计算红色 alpha 和判断是否过阈值
    var rawDrag by remember { mutableFloatStateOf(0f) }
    // 卡片圆角，与 Card 默认 shapes.medium 一致
    val cardShape = MaterialTheme.shapes.medium

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // 阈值 = 卡片宽度的 30%
        val thresholdPx = with(density) { maxWidth.toPx() * 0.3f }
        // 卡片完整宽度（用于滑出动画目标值）
        val fullWidthPx = with(density) { maxWidth.toPx() }
        // 提前在 Composable 上下文中取色，供 drawBehind 使用
        val errorColor = MaterialTheme.colorScheme.error
        val onErrorColor = MaterialTheme.colorScheme.onError

        // 背景层：红色预警（与卡片同圆角，覆盖完整宽度）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(cardShape)
                .drawBehind {
                    val alpha = (rawDrag / thresholdPx).coerceIn(0f, 1f)
                    drawRect(errorColor.copy(alpha = alpha))
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Icon(
                imageVector = HugeIcons.Delete01,
                contentDescription = null,
                modifier = Modifier.padding(end = 20.dp),
                tint = onErrorColor.copy(
                    alpha = (rawDrag / thresholdPx).coerceIn(0f, 1f),
                ),
            )
        }

        // 前景层：卡片内容，跟随手指左移
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offset.value }
                .then(
                    if (enabled) {
                        Modifier.pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    val reached = rawDrag >= thresholdPx
                                    if (reached) {
                                        // 先动画滑出屏幕左侧，再回调删除
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
                                // dragAmount < 0 为左滑（手指向左）；只允许左滑
                                val next = (rawDrag - dragAmount).coerceAtLeast(0f)
                                rawDrag = next
                                // 超过阈值后施加阻尼：显示位移 = 阈值 + 超出部分 × 阻尼系数
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
    }
}

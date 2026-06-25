package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 可复用的"列表页骨架"组件，消除设置/扩展页面重复的页面级样板代码。
 *
 * 内部组合 [Scaffold] + [LargeFlexibleTopAppBar]（可选 [BackButton] 与 actions）
 * + [LazyColumn] + [ReorderableSwipeableItem]，统一了拖拽排序、滑动删除、空状态、
 * 列表后追加内容等常见结构。各页面只需传入差异参数（标题、actions、item 渲染等）。
 *
 * 视觉与交互与原来逐页手写的骨架保持一致：
 * - 顶部栏使用 [CustomColors.topBarColors]，容器色同理；
 * - 列表项间距 12.dp，contentPadding 为 Scaffold innerPadding + [contentPadding]；
 * - 空列表时渲染 [emptyContent]（作为列表 item）；不传则不渲染空状态；
 * - [extraContent] 在列表项之后追加（始终渲染，无论是否为空）。
 *
 * @param title           顶部栏标题
 * @param items           列表数据
 * @param itemKey         列表项 key（与 items key 一致）
 * @param onReorder       拖拽排序回调（from/to 为列表索引）
 * @param onDelete        滑动删除回调
 * @param itemContent     列表项内容（通常是 [ListCard]）
 * @param modifier        Scaffold 修饰符
 * @param onBack          非 null 时显示返回按钮（[BackButton] 内部使用 LocalNavController）
 * @param actions         顶部栏右侧操作
 * @param emptyContent    空状态内容；null 表示不渲染空状态
 * @param dragEnabled     是否允许长按拖拽排序
 * @param swipeEnabled    是否允许滑动删除
 * @param contentPadding  列表基础内边距（会与 Scaffold innerPadding 相加）
 * @param extraContent    列表项之后追加的内容（LazyListScope 扩展）
 * @param animateItem     是否为列表项启用 animateItem 修饰符
 * @param applyImePadding 是否为列表启用 imePadding
 */
@Composable
fun <T> ReorderableListScaffold(
    title: String,
    items: List<T>,
    itemKey: (T) -> Any,
    onReorder: (from: Int, to: Int) -> Unit,
    onDelete: (T) -> Unit,
    itemContent: @Composable (T) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    emptyContent: (@Composable () -> Unit)? = null,
    dragEnabled: Boolean = true,
    swipeEnabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    extraContent: (LazyListScope.() -> Unit)? = null,
    animateItem: Boolean = false,
    applyImePadding: Boolean = false,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onReorder(from.index, to.index)
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        BackButton()
                    }
                },
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(if (applyImePadding) Modifier.imePadding() else Modifier),
            contentPadding = innerPadding + contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            state = lazyListState,
        ) {
            if (items.isEmpty()) {
                emptyContent?.let { content ->
                    item { content() }
                }
            } else {
                items(items, key = itemKey) { item ->
                    ReorderableSwipeableItem(
                        onDelete = { onDelete(item) },
                        state = reorderableState,
                        key = itemKey(item),
                        modifier = if (animateItem) Modifier.animateItem() else Modifier,
                        swipeEnabled = swipeEnabled,
                        dragEnabled = dragEnabled,
                    ) {
                        itemContent(item)
                    }
                }
            }
            extraContent?.invoke(this)
        }
    }
}

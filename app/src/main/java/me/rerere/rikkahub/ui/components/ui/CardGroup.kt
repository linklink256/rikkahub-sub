package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.Spacing

private val CardGroupCorner = 16.dp
private val CardGroupItemSpacing = Spacing.xs
private val CardGroupInnerCorner = 4.dp

private sealed class CardGroupEntry {
    abstract val onClick: (() -> Unit)?
    abstract val modifier: Modifier
}

private data class CardGroupListItemEntry(
    override val onClick: (() -> Unit)?,
    override val modifier: Modifier,
    val overlineContent: (@Composable () -> Unit)?,
    val headlineContent: @Composable () -> Unit,
    val supportingContent: (@Composable () -> Unit)?,
    val leadingContent: (@Composable () -> Unit)?,
    val trailingContent: (@Composable () -> Unit)?,
    val colors: ListItemColors?,
) : CardGroupEntry()

private data class CardGroupFormItemEntry(
    override val onClick: (() -> Unit)?,
    override val modifier: Modifier,
    val label: @Composable () -> Unit,
    val description: (@Composable () -> Unit)?,
    val tail: @Composable () -> Unit,
    val content: @Composable ColumnScope.() -> Unit,
) : CardGroupEntry()

@DslMarker
private annotation class CardGroupDsl

@CardGroupDsl
interface CardGroupScope {
    fun item(
        onClick: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        overlineContent: (@Composable () -> Unit)? = null,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        colors: ListItemColors? = null,
        headlineContent: @Composable () -> Unit,
    )

    fun formItem(
        onClick: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        label: @Composable () -> Unit,
        description: (@Composable () -> Unit)? = null,
        tail: @Composable () -> Unit = {},
        content: @Composable ColumnScope.() -> Unit = {},
    )
}

private class CardGroupScopeImpl : CardGroupScope {
    val entries = mutableListOf<CardGroupEntry>()

    override fun item(
        onClick: (() -> Unit)?,
        modifier: Modifier,
        overlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        colors: ListItemColors?,
        headlineContent: @Composable () -> Unit,
    ) {
        entries.add(
            CardGroupListItemEntry(
                onClick = onClick,
                modifier = modifier,
                overlineContent = overlineContent,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = colors,
            )
        )
    }

    override fun formItem(
        onClick: (() -> Unit)?,
        modifier: Modifier,
        label: @Composable () -> Unit,
        description: (@Composable () -> Unit)?,
        tail: @Composable () -> Unit,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        entries.add(
            CardGroupFormItemEntry(
                onClick = onClick,
                modifier = modifier,
                label = label,
                description = description,
                tail = tail,
                content = content,
            )
        )
    }
}

@Composable
private fun CardGroupListItem(
    item: CardGroupListItemEntry,
    count: Int,
    index: Int,
) {
    val isFirst = index == 0
    val isLast = index == count - 1

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val topCorner by animateDpAsState(
        targetValue = if (isPressed || count == 1 || isFirst) CardGroupCorner else CardGroupInnerCorner,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )
    val bottomCorner by animateDpAsState(
        targetValue = if (isPressed || count == 1 || isLast) CardGroupCorner else CardGroupInnerCorner,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )

    ListItem(
        headlineContent = item.headlineContent,
        modifier = item.modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    topStart = topCorner,
                    topEnd = topCorner,
                    bottomStart = bottomCorner,
                    bottomEnd = bottomCorner,
                )
            )
            .then(
                if (item.onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = item.onClick,
                    )
                } else Modifier
            ),
        overlineContent = item.overlineContent,
        supportingContent = item.supportingContent,
        leadingContent = item.leadingContent,
        trailingContent = item.trailingContent,
        colors = item.colors ?: CustomColors.listItemColors,
    )
}

@Composable
private fun CardGroupFormItem(
    item: CardGroupFormItemEntry,
    count: Int,
    index: Int,
) {
    val isFirst = index == 0
    val isLast = index == count - 1

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val topCorner by animateDpAsState(
        targetValue = if (isPressed || count == 1 || isFirst) CardGroupCorner else CardGroupInnerCorner,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )
    val bottomCorner by animateDpAsState(
        targetValue = if (isPressed || count == 1 || isLast) CardGroupCorner else CardGroupInnerCorner,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )

    Box(
        modifier = item.modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    topStart = topCorner,
                    topEnd = topCorner,
                    bottomStart = bottomCorner,
                    bottomEnd = bottomCorner,
                )
            )
            .background(MaterialTheme.colorScheme.surfaceBright)
            .then(
                if (item.onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = item.onClick,
                    )
                } else Modifier
            )
    ) {
        FormItem(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            label = item.label,
            description = item.description,
            tail = item.tail,
            content = item.content,
        )
    }
}

@Composable
fun CardGroup(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    content: CardGroupScope.() -> Unit,
) {
    val scope = CardGroupScopeImpl()
    scope.content()

    Column(modifier = modifier) {
        if (title != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                ProvideTextStyle(MaterialTheme.typography.titleSmallEmphasized) {
                    Box(modifier = Modifier.padding(start = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm)) {
                        title()
                    }
                }
            }
        }
        val count = scope.entries.size
        scope.entries.fastForEachIndexed { index, entry ->
            when (entry) {
                is CardGroupListItemEntry -> CardGroupListItem(item = entry, count = count, index = index)
                is CardGroupFormItemEntry -> CardGroupFormItem(item = entry, count = count, index = index)
            }
            if (index != count - 1) {
                Spacer(modifier = Modifier.height(CardGroupItemSpacing))
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun CardGroupPreview() {
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text("Card Group")
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            CardGroup(
                modifier = Modifier.padding(horizontal = 16.dp),
                title = { Text("About") },
            ) {
                item(
                    headlineContent = { Text("第一项") },
                )
                item(
                    headlineContent = { Text("第二项") },
                    supportingContent = { Text("支持文本") },
                )
                item(
                    onClick = {},
                    headlineContent = { Text("第三项") },
                    trailingContent = { Text("→") },
                )
            }
        }
    }
}

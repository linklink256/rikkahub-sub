package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.subagent.SubagentProfile
import me.rerere.rikkahub.data.ai.subagent.mergeSubagentProfiles
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantSubagentPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_tab_subagent)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantSubagentContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            onUpdate = { vm.update(it) },
            onOpenProfile = { profileName ->
                navController.navigate(Screen.AssistantSubagentProfile(id, profileName))
            },
        )
    }
}

@Composable
private fun AssistantSubagentContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val profiles = mergeSubagentProfiles(assistant.subagentProfiles)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- 总开关 + 最大深度 ----
        item {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FormItem(
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.subagent_enable_title)) },
                        description = { Text(stringResource(R.string.subagent_enable_desc)) },
                        tail = {
                            Switch(
                                checked = assistant.enableSubagents,
                                onCheckedChange = {
                                    onUpdate(assistant.copy(enableSubagents = it))
                                },
                            )
                        },
                    )
                    HorizontalDivider()
                    FormItem(
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.subagent_max_depth_title)) },
                        description = {
                            Text(
                                stringResource(
                                    R.string.subagent_max_depth_desc,
                                    assistant.subagentMaxDepth,
                                )
                            )
                        },
                    ) {
                        Slider(
                            value = assistant.subagentMaxDepth.toFloat(),
                            onValueChange = {
                                onUpdate(
                                    assistant.copy(
                                        subagentMaxDepth = it.toInt().coerceIn(1, 5)
                                    )
                                )
                            },
                            valueRange = 1f..5f,
                            steps = 3,
                        )
                    }
                }
            }
        }

        // ---- profile 列表标题 ----
        item {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.subagent_profiles_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.subagent_profiles_section_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- profile 卡片 ----
        items(profiles.size) { index ->
            val profile = profiles[index]
            CardGroup(modifier = Modifier.fillMaxWidth()) {
                item(
                    onClick = { onOpenProfile(profile.name) },
                    leadingContent = { Icon(HugeIcons.Connect, null) },
                    overlineContent = { Text(profile.name) },
                    headlineContent = {
                        Text(profile.displayName.ifBlank { profile.name })
                    },
                    supportingContent = {
                        Text(
                            text = profile.description.ifBlank { profile.name },
                            maxLines = 2,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                )
            }
        }
    }
}

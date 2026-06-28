package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.ListCard
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.components.ui.ReorderableListScaffold
import me.rerere.rikkahub.ui.components.ui.SectionHeader
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.move
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingSearchPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    var showAddDialog by remember { mutableStateOf(false) }

    ReorderableListScaffold(
        title = stringResource(R.string.setting_page_search_title),
        items = settings.searchServices,
        itemKey = { it.id },
        onReorder = { from, to ->
            if (from >= 0 && to >= 0 && from < settings.searchServices.size && to < settings.searchServices.size) {
                val newServices = settings.searchServices.move(from, to)
                vm.updateSettings(settings.copy(searchServices = newServices))
            }
        },
        onDelete = { service ->
            if (settings.searchServices.size > 1) {
                val index = settings.searchServices.indexOf(service)
                val newServices = settings.searchServices.toMutableList()
                newServices.removeAt(index)
                vm.updateSettings(settings.copy(searchServices = newServices))
            }
        },
        onBack = {},
        actions = {
            IconButton(onClick = { showAddDialog = true }) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = stringResource(R.string.setting_page_search_add_provider),
                )
            }
        },
        extraContent = {
            item("common_options") {
                CommonOptions(
                    settings = settings,
                    onUpdate = { options ->
                        vm.updateSettings(
                            settings.copy(searchCommonOptions = options)
                        )
                    }
                )
            }
        },
        animateItem = true,
        applyImePadding = true,
        itemContent = { service ->
            ListCard(
                onClick = {
                    nav.navigate(Screen.SettingSearchDetail(service.id.toString()))
                },
                leading = {
                    AutoAIIcon(name = service.displayName)
                },
                title = service.displayName,
                tags = {
                    SearchAbilityTagLine(options = service)
                },
            )
        },
    )

    if (showAddDialog) {
        AddProviderDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { options ->
                showAddDialog = false
                vm.updateSettings(
                    settings.copy(
                        searchServices = listOf(options) + settings.searchServices
                    )
                )
            }
        )
    }
}

@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onConfirm: (SearchServiceOptions) -> Unit
) {
    var selectedType by remember {
        mutableStateOf(SearchServiceOptions.TYPES.keys.first())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.setting_page_search_add_provider))
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(SearchServiceOptions.TYPES.keys.toList()) { type ->
                    val name = SearchServiceOptions.TYPES[type] ?: "Unknown"
                    val isSelected = selectedType == type
                    Card(
                        onClick = { selectedType = type },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AutoAIIcon(
                                name = name,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(SearchServiceOptions.create(selectedType))
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun SearchProviderCard_removed(
    @Suppress("UNUSED_PARAMETER") service: SearchServiceOptions,
    @Suppress("UNUSED_PARAMETER") onEdit: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onDelete: () -> Unit,
    @Suppress("UNUSED_PARAMETER") canDelete: Boolean,
    @Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier,
) {
}

@Composable
fun SearchAbilityTagLine(
    modifier: Modifier = Modifier,
    options: SearchServiceOptions
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Tag(
            type = TagType.DEFAULT,
        ) {
            Text(stringResource(R.string.search_ability_search))
        }
        if (SearchService.getService(options).scrapingParameters(options) != null) {
            Tag(
                type = TagType.DEFAULT,
            ) {
                Text(stringResource(R.string.search_ability_scrape))
            }
        }
    }
}

@Composable
private fun CommonOptions(
    settings: me.rerere.rikkahub.data.datastore.Settings,
    onUpdate: (SearchCommonOptions) -> Unit
) {
    var commonOptions by remember(settings.searchCommonOptions) {
        mutableStateOf(settings.searchCommonOptions)
    }
    Column {
        SectionHeader(stringResource(R.string.setting_page_search_common_options))
        FormItem(
            label = {
                Text(stringResource(R.string.setting_page_search_result_size))
            }
        ) {
            OutlinedNumberInput(
                value = commonOptions.resultSize,
                onValueChange = {
                    commonOptions = commonOptions.copy(resultSize = it)
                    onUpdate(commonOptions)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

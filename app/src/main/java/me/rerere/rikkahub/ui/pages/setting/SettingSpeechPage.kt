package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.VolumeHigh
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.ListCard
import me.rerere.rikkahub.ui.components.ui.ProviderEditSheet
import me.rerere.rikkahub.ui.components.ui.ReorderableSwipeableItem
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.pages.setting.components.ASRProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.TTSProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.typeDisplayName
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun SettingSpeechPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editingTTSProvider by remember { mutableStateOf<TTSProviderSetting?>(null) }
    var editingASRProvider by remember { mutableStateOf<ASRProviderSetting?>(null) }
    var selectedPage by remember { mutableIntStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.speech_page_title))
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (selectedPage == 0) {
                        AddTTSProviderButton {
                            vm.addTtsProvider(it)
                        }
                    } else {
                        AddASRProviderButton {
                            vm.addAsrProvider(it)
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor
            ) {
                NavigationBarItem(
                    selected = selectedPage == 0,
                    onClick = { selectedPage = 0 },
                    icon = { Icon(HugeIcons.VolumeHigh, contentDescription = null) },
                    label = { Text(stringResource(R.string.speech_tab_tts)) }
                )
                NavigationBarItem(
                    selected = selectedPage == 1,
                    onClick = { selectedPage = 1 },
                    icon = { Icon(HugeIcons.Mic01, contentDescription = null) },
                    label = { Text(stringResource(R.string.speech_tab_asr)) }
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when (selectedPage) {
            0 -> TTSProviderList(
                settings = settings,
                onReorder = vm::reorderTtsProviders,
                onDelete = vm::deleteTtsProvider,
                onSelect = vm::selectTtsProvider,
                onEdit = { editingTTSProvider = it },
                modifier = Modifier.padding(innerPadding)
            )

            1 -> ASRProviderList(
                settings = settings,
                onReorder = vm::reorderAsrProviders,
                onDelete = vm::deleteAsrProvider,
                onSelect = vm::selectAsrProvider,
                onEdit = { editingASRProvider = it },
                modifier = Modifier.padding(innerPadding)
            )
        }
    }

    // Edit TTS Provider Bottom Sheet
    editingTTSProvider?.let { provider ->
        ProviderEditSheet(
            title = stringResource(R.string.setting_tts_page_edit_provider),
            confirmText = stringResource(R.string.chat_page_save),
            setting = provider,
            onConfirm = { edited ->
                vm.updateTtsProvider(edited)
                editingTTSProvider = null
            },
            onDismiss = { editingTTSProvider = null },
            configure = { setting, onValueChange, modifier ->
                TTSProviderConfigure(
                    setting = setting,
                    onValueChange = onValueChange,
                    modifier = modifier,
                )
            },
        )
    }

    editingASRProvider?.let { provider ->
        ProviderEditSheet(
            title = stringResource(R.string.setting_asr_page_edit_provider),
            confirmText = stringResource(R.string.chat_page_save),
            setting = provider,
            onConfirm = { edited ->
                vm.updateAsrProvider(edited)
                editingASRProvider = null
            },
            onDismiss = { editingASRProvider = null },
            configure = { setting, onValueChange, modifier ->
                ASRProviderConfigure(
                    setting = setting,
                    onValueChange = onValueChange,
                    modifier = modifier,
                )
            },
        )
    }
}

@Composable
private fun TTSProviderList(
    settings: Settings,
    onReorder: (Int, Int) -> Unit,
    onDelete: (TTSProviderSetting) -> Unit,
    onSelect: (TTSProviderSetting) -> Unit,
    onEdit: (TTSProviderSetting) -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onReorder(from.index, to.index)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        state = lazyListState
    ) {
        items(settings.ttsProviders, key = { it.id }) { provider ->
            ReorderableSwipeableItem(
                onDelete = { onDelete(provider) },
                state = reorderableState,
                key = provider.id,
            ) {
                TTSProviderItem(
                    provider = provider,
                    isSelected = settings.selectedTTSProviderId == provider.id,
                    onSelect = { onSelect(provider) },
                    onEdit = {
                        onEdit(provider)
                    },
                )
            }
        }
    }
}

@Composable
private fun ASRProviderList(
    settings: Settings,
    onReorder: (Int, Int) -> Unit,
    onDelete: (ASRProviderSetting) -> Unit,
    onSelect: (ASRProviderSetting) -> Unit,
    onEdit: (ASRProviderSetting) -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onReorder(from.index, to.index)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        state = lazyListState
    ) {
        items(settings.asrProviders, key = { it.id }) { provider ->
            ReorderableSwipeableItem(
                onDelete = { onDelete(provider) },
                state = reorderableState,
                key = provider.id,
            ) {
                ASRProviderItem(
                    provider = provider,
                    isSelected = settings.selectedASRProviderId == provider.id,
                    onSelect = { onSelect(provider) },
                    onEdit = {
                        onEdit(provider)
                    },
                )
            }
        }
    }
}

@Composable
private fun AddTTSProviderButton(onAdd: (TTSProviderSetting) -> Unit) {
    var showBottomSheet by remember { mutableStateOf(false) }

    IconButton(
        onClick = {
            showBottomSheet = true
        }
    ) {
        Icon(HugeIcons.Add01, stringResource(R.string.setting_tts_page_add_provider_content_description))
    }

    if (showBottomSheet) {
        ProviderEditSheet<TTSProviderSetting>(
            title = stringResource(R.string.setting_tts_page_add_provider),
            confirmText = stringResource(R.string.setting_tts_page_add),
            setting = TTSProviderSetting.SystemTTS(),
            onConfirm = {
                onAdd(it)
                showBottomSheet = false
            },
            onDismiss = { showBottomSheet = false },
            configure = { setting, onValueChange, modifier ->
                TTSProviderConfigure(
                    setting = setting,
                    onValueChange = onValueChange,
                    modifier = modifier,
                )
            },
        )
    }
}

@Composable
private fun AddASRProviderButton(onAdd: (ASRProviderSetting) -> Unit) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var showTypeMenu by remember { mutableStateOf(false) }
    var initialProvider by remember { mutableStateOf<ASRProviderSetting>(ASRProviderSetting.OpenAIRealtime()) }

    Box {
        IconButton(
            onClick = { showTypeMenu = true }
        ) {
            Icon(HugeIcons.Add01, stringResource(R.string.setting_asr_page_add_provider))
        }
        DropdownMenu(
            expanded = showTypeMenu,
            onDismissRequest = { showTypeMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("OpenAI Realtime") },
                onClick = {
                    initialProvider = ASRProviderSetting.OpenAIRealtime()
                    showTypeMenu = false
                    showBottomSheet = true
                }
            )
            DropdownMenuItem(
                text = { Text("DashScope") },
                onClick = {
                    initialProvider = ASRProviderSetting.DashScope()
                    showTypeMenu = false
                    showBottomSheet = true
                }
            )
            DropdownMenuItem(
                text = { Text("Volcengine") },
                onClick = {
                    initialProvider = ASRProviderSetting.Volcengine()
                    showTypeMenu = false
                    showBottomSheet = true
                }
            )
            DropdownMenuItem(
                text = { Text("MiMo") },
                onClick = {
                    initialProvider = ASRProviderSetting.MiMo()
                    showTypeMenu = false
                    showBottomSheet = true
                }
            )
            DropdownMenuItem(
                text = { Text("Step") },
                onClick = {
                    initialProvider = ASRProviderSetting.Step()
                    showTypeMenu = false
                    showBottomSheet = true
                }
            )
        }
    }

    if (showBottomSheet) {
        ProviderEditSheet(
            title = stringResource(R.string.setting_asr_page_add_provider),
            confirmText = stringResource(R.string.setting_tts_page_add),
            setting = initialProvider,
            onConfirm = {
                onAdd(it)
                showBottomSheet = false
            },
            onDismiss = { showBottomSheet = false },
            configure = { setting, onValueChange, modifier ->
                ASRProviderConfigure(
                    setting = setting,
                    onValueChange = onValueChange,
                    modifier = modifier,
                )
            },
        )
    }
}

@Composable
private fun TTSProviderItem(
    provider: TTSProviderSetting,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    val tts = LocalTTSState.current
    val isSpeaking by tts.isSpeaking.collectAsStateWithLifecycle()
    val isAvailable by tts.isAvailable.collectAsStateWithLifecycle()
    val providerType = provider.typeDisplayName()
    ListCard(
        onClick = onEdit,
        modifier = modifier,
        leading = {
            AutoAIIcon(
                name = provider.name.ifEmpty { stringResource(R.string.setting_tts_page_default_name) },
            )
        },
        title = provider.name.ifEmpty { stringResource(R.string.setting_tts_page_default_name) },
        tags = {
            Tag(type = TagType.DEFAULT) { Text(providerType) }
            if (isSelected) {
                Tag(type = TagType.SUCCESS) {
                    Text(stringResource(R.string.setting_tts_page_selected))
                }
            }
        },
        trailing = {
            if (isSelected && isAvailable) {
                val testText = stringResource(R.string.setting_tts_page_test_text)
                IconButton(
                    onClick = {
                        if (!isSpeaking) {
                            tts.speak(testText)
                        } else {
                            tts.stop()
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (isSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
                        contentDescription = if (isSpeaking) stringResource(R.string.stop) else stringResource(R.string.test_tts),
                        tint = if (isSpeaking) MaterialTheme.colorScheme.error else LocalContentColor.current
                    )
                }
            }
            RadioButton(
                selected = isSelected,
                onClick = onSelect
            )
        },
    )
}

@Composable
private fun ASRProviderItem(
    provider: ASRProviderSetting,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    val providerType = provider.typeDisplayName()
    ListCard(
        onClick = onEdit,
        modifier = modifier,
        leading = {
            AutoAIIcon(
                name = provider.name.ifEmpty { stringResource(R.string.setting_asr_page_default_name) },
            )
        },
        title = provider.name.ifEmpty { stringResource(R.string.setting_asr_page_default_name) },
        tags = {
            Tag(type = TagType.DEFAULT) { Text(providerType) }
            if (isSelected) {
                Tag(type = TagType.SUCCESS) {
                    Text(stringResource(R.string.setting_tts_page_selected))
                }
            }
        },
        trailing = {
            RadioButton(
                selected = isSelected,
                onClick = onSelect
            )
        },
    )
}

package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.FileDownload
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Share03
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.export.LorebookSerializer
import me.rerere.rikkahub.data.export.ModeInjectionSerializer
import me.rerere.rikkahub.data.export.rememberExporter
import me.rerere.rikkahub.data.export.rememberImporter
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ExportDialog
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.ListCard
import me.rerere.rikkahub.ui.components.ui.ReorderableListScaffold
import me.rerere.rikkahub.ui.components.ui.ReorderableSwipeableItem
import me.rerere.rikkahub.ui.components.ui.SectionHeader
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.EditState
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.move
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun ModeInjectionPage(vm: PromptVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modeInjections = settings.modeInjections
    val toaster = LocalToaster.current
    val importSuccessMsg = stringResource(R.string.export_import_success)
    val importFailedMsg = stringResource(R.string.export_import_failed)
    val importer = rememberImporter(ModeInjectionSerializer) { result ->
        result.onSuccess { imported ->
            vm.addOrUpdateModeInjection(imported)
            toaster.show(importSuccessMsg)
        }.onFailure { error ->
            toaster.show(importFailedMsg.format(error.message))
        }
    }
    val editState = useEditState<PromptInjection.ModeInjection> { edited ->
        vm.addOrUpdateModeInjection(edited)
    }

    ReorderableListScaffold(
        title = stringResource(R.string.prompt_page_mode_injection_tab),
        items = modeInjections,
        itemKey = { it.id },
        onReorder = { from, to ->
            vm.reorderModeInjections(from, to)
        },
        onDelete = { vm.deleteModeInjection(it) },
        onBack = {},
        actions = {
            IconButton(onClick = { editState.open(PromptInjection.ModeInjection()) }) {
                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.add))
            }
            IconButton(onClick = { importer.importFromFile() }) {
                Icon(HugeIcons.FileImport, contentDescription = null)
            }
        },
        emptyContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.prompt_page_mode_injection_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.prompt_page_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        },
        itemContent = { injection ->
            ModeInjectionCard(
                injection = injection,
                onEdit = { editState.open(injection) },
            )
        },
    )

    if (editState.isEditing) {
        editState.currentState?.let { state ->
            ModeInjectionEditSheet(
                injection = state,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it },
            )
        }
    }
}

@Composable
private fun ModeInjectionTab(
    modeInjections: List<PromptInjection.ModeInjection>,
    onUpdate: (List<PromptInjection.ModeInjection>) -> Unit,
    editState: EditState<PromptInjection.ModeInjection>,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newList = modeInjections.move(from.index, to.index)
        onUpdate(newList)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        state = lazyListState
    ) {
            if (modeInjections.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillParentMaxHeight(0.8f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.prompt_page_mode_injection_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.prompt_page_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                items(modeInjections, key = { it.id }) { injection ->
                    ReorderableSwipeableItem(
                        onDelete = { onUpdate(modeInjections - injection) },
                        state = reorderableState,
                        key = injection.id,
                    ) {
                        ModeInjectionCard(
                            injection = injection,
                            onEdit = { editState.open(injection) },
                        )
                    }
                }
            }
        }


    if (editState.isEditing) {
        editState.currentState?.let { state ->
            ModeInjectionEditSheet(
                injection = state,
                onDismiss = { editState.dismiss() },
                onConfirm = { editState.confirm() },
                onEdit = { editState.currentState = it }
            )
        }
    }
}

@Composable
private fun ModeInjectionCard(
    injection: PromptInjection.ModeInjection,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
) {
    var showExportDialog by remember { mutableStateOf(false) }
    val exporter = rememberExporter(injection, ModeInjectionSerializer)

    ListCard(
        onClick = onEdit,
        modifier = modifier,
        title = injection.name.ifEmpty { stringResource(R.string.prompt_page_unnamed) },
        tags = {
            Tag(type = TagType.INFO) {
                Text(getPositionLabel(injection.position))
            }
            Tag(type = TagType.DEFAULT) {
                Text(stringResource(R.string.prompt_page_priority_format, injection.priority))
            }
            if (!injection.enabled) {
                Tag(type = TagType.WARNING) {
                    Text(stringResource(R.string.prompt_page_disabled))
                }
            }
        },
        trailing = {
            IconButton(onClick = { showExportDialog = true }) {
                Icon(HugeIcons.Share03, stringResource(R.string.export_title))
            }
        },
    )

    if (showExportDialog) {
        ExportDialog(
            exporter = exporter,
            onDismiss = { showExportDialog = false }
        )
    }
}

@Composable
private fun ModeInjectionEditSheet(
    injection: PromptInjection.ModeInjection,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onEdit: (PromptInjection.ModeInjection) -> Unit
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(onClick = {
                scope.launch {
                    sheetState.hide()
                    onDismiss()
                }
            }) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.prompt_page_edit_mode_injection),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = injection.name,
                    onValueChange = { onEdit(injection.copy(name = it)) },
                    label = { Text(stringResource(R.string.prompt_page_name)) },
                    modifier = Modifier.fillMaxWidth()
                )

                FormItem(
                    label = { Text(stringResource(R.string.prompt_page_enabled)) },
                    tail = {
                        Switch(
                            checked = injection.enabled,
                            onCheckedChange = { onEdit(injection.copy(enabled = it)) }
                        )
                    }
                )

                OutlinedTextField(
                    value = injection.priority.toString(),
                    onValueChange = {
                        it.toIntOrNull()?.let { p -> onEdit(injection.copy(priority = p)) }
                    },
                    label = { Text(stringResource(R.string.prompt_page_priority_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                SectionHeader(stringResource(R.string.prompt_page_injection_position))
                InjectionPositionSelector(
                    position = injection.position,
                    onSelect = { onEdit(injection.copy(position = it)) }
                )

                AnimatedVisibility(visible = injection.position == InjectionPosition.AT_DEPTH) {
                    OutlinedTextField(
                        value = injection.injectDepth.toString(),
                        onValueChange = {
                            it.toIntOrNull()?.let { d -> onEdit(injection.copy(injectDepth = d)) }
                        },
                        label = { Text(stringResource(R.string.prompt_page_inject_depth)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                AnimatedVisibility(visible = injection.position.usesStandaloneMessage()) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionHeader(stringResource(R.string.prompt_page_injection_role))
                        InjectionRoleSelector(
                            role = injection.role,
                            onSelect = { onEdit(injection.copy(role = it)) }
                        )
                    }
                }

                OutlinedTextField(
                    value = injection.content,
                    onValueChange = { onEdit(injection.copy(content = it)) },
                    label = { Text(stringResource(R.string.prompt_page_injection_content)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    minLines = 5
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.prompt_page_cancel))
                }
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.prompt_page_confirm))
                }
            }
        }
    }
}

@Composable
internal fun InjectionPositionSelector(
    position: InjectionPosition,
    onSelect: (InjectionPosition) -> Unit
) {
    Select(
        options = InjectionPosition.entries,
        selectedOption = position,
        onOptionSelected = onSelect,
        optionToString = { getPositionLabel(it) },
        modifier = Modifier.fillMaxWidth()
    )
}

internal fun InjectionPosition.usesStandaloneMessage(): Boolean = when (this) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT,
    InjectionPosition.AFTER_SYSTEM_PROMPT -> false

    InjectionPosition.TOP_OF_CHAT,
    InjectionPosition.BOTTOM_OF_CHAT,
    InjectionPosition.AT_DEPTH -> true
}

@Composable
internal fun getPositionLabel(position: InjectionPosition): String = when (position) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_before_system)
    InjectionPosition.AFTER_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_after_system)
    InjectionPosition.TOP_OF_CHAT -> stringResource(R.string.prompt_page_position_top_of_chat)
    InjectionPosition.BOTTOM_OF_CHAT -> stringResource(R.string.prompt_page_position_bottom_of_chat)
    InjectionPosition.AT_DEPTH -> stringResource(R.string.prompt_page_position_at_depth)
}

@Composable
internal fun InjectionRoleSelector(
    role: MessageRole,
    onSelect: (MessageRole) -> Unit
) {
    Select(
        options = listOf(MessageRole.USER, MessageRole.ASSISTANT),
        selectedOption = role,
        onOptionSelected = onSelect,
        optionToString = { getRoleLabel(it) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun getRoleLabel(role: MessageRole): String = when (role) {
    MessageRole.USER -> stringResource(R.string.prompt_page_role_user)
    MessageRole.ASSISTANT -> stringResource(R.string.prompt_page_role_assistant)
    else -> role.name
}

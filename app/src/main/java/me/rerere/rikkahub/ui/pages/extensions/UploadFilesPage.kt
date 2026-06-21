package me.rerere.rikkahub.ui.pages.extensions

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Sorting01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromUri
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SortMode { NAME, SIZE, TIME }

@Composable
fun UploadFilesPage(
    filesManager: FilesManager = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current

    val deletedToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedToast = stringResource(R.string.setting_files_page_delete_failed_toast)
    val openFailedToast = stringResource(R.string.upload_files_page_open_failed)
    val importSuccessToast = stringResource(R.string.upload_files_page_import_success)
    val importFailedToast = stringResource(R.string.upload_files_page_import_failed)

    val allFiles by filesManager.observe(FileFolders.UPLOAD).collectAsState(initial = emptyList())

    // Selection state
    var inSelection by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }

    // Filter / sort state
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.TIME) }
    var sortAsc by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Dialogs
    var pendingDeleteOne by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var pendingDeleteBatch by remember { mutableStateOf(false) }
    var previewImages by remember { mutableStateOf<List<String>?>(null) }

    val visibleFiles by remember(allFiles, query, sortMode, sortAsc) {
        derivedStateOf {
            val filtered = if (query.isBlank()) {
                allFiles
            } else {
                allFiles.filter {
                    it.displayName.contains(query, ignoreCase = true)
                }
            }
            val cmp = Comparator<ManagedFileEntity> { a, b ->
                val r = when (sortMode) {
                    SortMode.NAME -> a.displayName.compareTo(b.displayName, ignoreCase = true)
                    SortMode.SIZE -> a.sizeBytes.compareTo(b.sizeBytes)
                    SortMode.TIME -> a.createdAt.compareTo(b.createdAt)
                }
                if (sortAsc) r else -r
            }
            filtered.sortedWith(cmp)
        }
    }

    val totalCount = allFiles.size
    val totalSize = remember(allFiles) {
        allFiles.sumOf { it.sizeBytes }
    }

    // File import launcher (any type)
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var ok = 0
            uris.forEach { uri ->
                runCatching {
                    filesManager.saveUploadFromUri(uri)
                }.onSuccess { ok++ }
            }
            if (ok > 0) {
                toaster.show(importSuccessToast.format(ok))
            } else {
                toaster.show(importFailedToast)
            }
        }
    }

    // --- Delete confirmations ---
    pendingDeleteOne?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteOne = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = { Text(target.displayName) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ok = filesManager.delete(target.id, deleteFromDisk = true)
                            if (ok) toaster.show(deletedToast) else toaster.show(deleteFailedToast)
                            pendingDeleteOne = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteOne = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    if (pendingDeleteBatch) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { pendingDeleteBatch = false },
            title = { Text(stringResource(R.string.upload_files_page_delete_selected_title, count)) },
            text = { Text(stringResource(R.string.upload_files_page_selected_count, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ids = selectedIds.toList()
                            var failed = 0
                            ids.forEach { id ->
                                val ok = filesManager.delete(id, deleteFromDisk = true)
                                if (!ok) failed++
                            }
                            if (failed == 0) {
                                toaster.show(deletedToast)
                            } else {
                                toaster.show(deleteFailedToast)
                            }
                            selectedIds.clear()
                            pendingDeleteBatch = false
                            inSelection = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteBatch = false }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    previewImages?.let { images ->
        ImagePreviewDialog(
            images = images,
            onDismissRequest = { previewImages = null }
        )
    }

    Scaffold(
        topBar = {
            if (inSelection) {
                TopAppBar(
                    title = { Text(stringResource(R.string.upload_files_page_selected_count, selectedIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = {
                            selectedIds.clear()
                            inSelection = false
                        }) {
                            Icon(HugeIcons.Cancel01, stringResource(R.string.setting_files_page_cancel_action))
                        }
                    },
                    actions = {
                        IconButton(onClick = { pendingDeleteBatch = true }) {
                            Icon(HugeIcons.Delete01, stringResource(R.string.upload_files_page_delete_selected))
                        }
                    },
                    colors = CustomColors.topBarColors,
                )
            } else {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.upload_files_page_title)) },
                    navigationIcon = { BackButton() },
                    actions = {
                        IconButton(onClick = { importLauncher.launch("*/*") }) {
                            Icon(HugeIcons.Upload02, stringResource(R.string.upload_files_page_import_button))
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(HugeIcons.Sorting01, stringResource(R.string.upload_files_page_sort_by))
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                SortMenuEntry(
                                    label = stringResource(R.string.upload_files_page_sort_name),
                                    active = sortMode == SortMode.NAME,
                                    asc = sortAsc,
                                    onClick = {
                                        if (sortMode == SortMode.NAME) {
                                            sortAsc = !sortAsc
                                        } else {
                                            sortMode = SortMode.NAME
                                            sortAsc = true
                                        }
                                        showSortMenu = false
                                    }
                                )
                                SortMenuEntry(
                                    label = stringResource(R.string.upload_files_page_sort_size),
                                    active = sortMode == SortMode.SIZE,
                                    asc = sortAsc,
                                    onClick = {
                                        if (sortMode == SortMode.SIZE) {
                                            sortAsc = !sortAsc
                                        } else {
                                            sortMode = SortMode.SIZE
                                            sortAsc = false
                                        }
                                        showSortMenu = false
                                    }
                                )
                                SortMenuEntry(
                                    label = stringResource(R.string.upload_files_page_sort_time),
                                    active = sortMode == SortMode.TIME,
                                    asc = sortAsc,
                                    onClick = {
                                        if (sortMode == SortMode.TIME) {
                                            sortAsc = !sortAsc
                                        } else {
                                            sortMode = SortMode.TIME
                                            sortAsc = false
                                        }
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                        IconButton(onClick = { inSelection = true }) {
                            Icon(HugeIcons.Tick01, stringResource(R.string.upload_files_page_select))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = CustomColors.topBarColors
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Stats + search (only in non-selection mode)
            if (!inSelection) {
                Text(
                    text = stringResource(R.string.upload_files_page_stats, totalCount, formatBytes(totalSize)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                )

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.upload_files_page_search_hint)) },
                    leadingIcon = { Icon(HugeIcons.Search01, null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                HorizontalDivider()
            }

            if (visibleFiles.isEmpty()) {
                EmptyState(
                    hasQuery = query.isNotBlank(),
                    onImport = { importLauncher.launch("*/*") }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(visibleFiles, key = { it.id }) { file ->
                        UploadFileItem(
                            file = file,
                            fileOnDisk = filesManager.getFile(file),
                            inSelection = inSelection,
                            selected = selectedIds.contains(file.id),
                            onToggleSelection = {
                                if (selectedIds.contains(file.id)) {
                                    selectedIds.remove(file.id)
                                } else {
                                    selectedIds.add(file.id)
                                }
                            },
                            onOpen = {
                                openFile(
                                    context = context,
                                    file = file,
                                    fileOnDisk = filesManager.getFile(file),
                                    onPreviewImage = { previewImages = it },
                                    onError = { toaster.show(openFailedToast) }
                                )
                            },
                            onDelete = { pendingDeleteOne = file }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SortMenuEntry(
    label: String,
    active: Boolean,
    asc: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            Text(
                text = if (active) (if (asc) "↑" else "↓") else "",
                color = MaterialTheme.colorScheme.primary
            )
        },
        onClick = onClick
    )
}

@Composable
private fun EmptyState(
    hasQuery: Boolean,
    onImport: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = HugeIcons.File02,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = stringResource(
                    if (hasQuery) R.string.setting_files_page_no_files
                    else R.string.upload_files_page_empty_title
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!hasQuery) {
                Text(
                    text = stringResource(R.string.upload_files_page_empty_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onImport) {
                    Icon(HugeIcons.Upload02, null, modifier = Modifier.size(18.dp))
                    Spacer8()
                    Text(stringResource(R.string.upload_files_page_empty_import))
                }
            }
        }
    }
}

@Composable
private fun UploadFileItem(
    file: ManagedFileEntity,
    fileOnDisk: File,
    inSelection: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    ListItem(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clickable {
                if (inSelection) onToggleSelection() else onOpen()
            },
        colors = ListItemDefaults.colors(containerColor = CustomColors.listItemColors.containerColor),
        leadingContent = {
            if (inSelection) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() }
                )
            } else if (file.mimeType.startsWith("image/")) {
                AsyncImage(
                    model = fileOnDisk,
                    contentDescription = file.displayName,
                    modifier = Modifier
                        .padding(4.dp)
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Icon(
                    imageVector = HugeIcons.File02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        headlineContent = {
            Text(
                text = file.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = "${file.mimeType} · ${formatBytes(file.sizeBytes)} · ${dateFormat.format(Date(file.createdAt))}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        },
        trailingContent = {
            if (!inSelection) {
                IconButton(onClick = onDelete) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.setting_files_page_delete_content_description)
                    )
                }
            }
        }
    )
}

private fun openFile(
    context: android.content.Context,
    file: ManagedFileEntity,
    fileOnDisk: File,
    onPreviewImage: (List<String>) -> Unit,
    onError: () -> Unit,
) {
    if (!fileOnDisk.exists()) {
        onError()
        return
    }
    if (file.mimeType.startsWith("image/")) {
        onPreviewImage(listOf(fileOnDisk.absolutePath))
        return
    }
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            fileOnDisk
        )
        intent.setDataAndType(uri, file.mimeType)
        val chooser = Intent.createChooser(intent, null)
        context.startActivity(chooser)
    }.onFailure { onError() }
}

@Composable
private fun Spacer8() {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(4.dp))
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1fKB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1fMB", mb)
    val gb = mb / 1024.0
    return String.format("%.1fGB", gb)
}

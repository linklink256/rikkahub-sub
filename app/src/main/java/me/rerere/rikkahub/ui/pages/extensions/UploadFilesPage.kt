package me.rerere.rikkahub.ui.pages.extensions

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Sorting01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromUri
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
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

    var inSelection by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }

    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.TIME) }
    var sortAsc by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    var deleteTarget by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var previewImages by remember { mutableStateOf<List<String>?>(null) }

    val visibleFiles by remember(allFiles, query, sortMode, sortAsc) {
        derivedStateOf {
            val filtered = if (query.isBlank()) {
                allFiles
            } else {
                allFiles.filter { it.displayName.contains(query, ignoreCase = true) }
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
    val totalSize = remember(allFiles) { allFiles.sumOf { it.sizeBytes } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var ok = 0
            uris.forEach { uri ->
                runCatching { filesManager.saveUploadFromUri(uri) }.onSuccess { ok++ }
            }
            if (ok > 0) {
                toaster.show(importSuccessToast.format(ok))
            } else {
                toaster.show(importFailedToast)
            }
        }
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
                            Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showBatchDeleteDialog = true }) {
                            Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.upload_files_page_delete_selected))
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
                            Icon(HugeIcons.Upload02, contentDescription = stringResource(R.string.upload_files_page_import_button))
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(HugeIcons.Sorting01, contentDescription = stringResource(R.string.upload_files_page_sort_by))
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                SortMenuItem(
                                    label = stringResource(R.string.upload_files_page_sort_name),
                                    active = sortMode == SortMode.NAME,
                                    asc = sortAsc,
                                ) {
                                    if (sortMode == SortMode.NAME) sortAsc = !sortAsc
                                    else { sortMode = SortMode.NAME; sortAsc = true }
                                    showSortMenu = false
                                }
                                SortMenuItem(
                                    label = stringResource(R.string.upload_files_page_sort_size),
                                    active = sortMode == SortMode.SIZE,
                                    asc = sortAsc,
                                ) {
                                    if (sortMode == SortMode.SIZE) sortAsc = !sortAsc
                                    else { sortMode = SortMode.SIZE; sortAsc = false }
                                    showSortMenu = false
                                }
                                SortMenuItem(
                                    label = stringResource(R.string.upload_files_page_sort_time),
                                    active = sortMode == SortMode.TIME,
                                    asc = sortAsc,
                                ) {
                                    if (sortMode == SortMode.TIME) sortAsc = !sortAsc
                                    else { sortMode = SortMode.TIME; sortAsc = false }
                                    showSortMenu = false
                                }
                            }
                        }
                        IconButton(onClick = { inSelection = true }) {
                            Icon(HugeIcons.Tick01, contentDescription = stringResource(R.string.upload_files_page_select))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = CustomColors.topBarColors,
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 搜索框 (模仿 AssistantPage)
            if (!inSelection) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    placeholder = { Text(stringResource(R.string.upload_files_page_search_hint)) },
                    leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(HugeIcons.Cancel01, contentDescription = null)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )

                // 统计
                Text(
                    text = stringResource(R.string.upload_files_page_stats, totalCount, formatBytes(totalSize)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (visibleFiles.isEmpty()) {
                // 空状态 (模仿 SkillsPage / QuickMessagesPage)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.File02,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            if (query.isNotBlank()) R.string.setting_files_page_no_files
                            else R.string.upload_files_page_empty_title
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.upload_files_page_empty_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visibleFiles, key = { it.id }) { file ->
                        UploadFileCard(
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
                            onDelete = { deleteTarget = file },
                        )
                    }
                }
            }
        }
    }

    // 单项删除确认 (模仿 SkillsPage / QuickMessagesPage 用 RikkaConfirmDialog)
    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.setting_files_page_delete_file_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { target ->
                scope.launch {
                    val ok = filesManager.delete(target.id, deleteFromDisk = true)
                    if (ok) toaster.show(deletedToast) else toaster.show(deleteFailedToast)
                }
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(deleteTarget?.displayName ?: "")
    }

    // 批量删除确认
    RikkaConfirmDialog(
        show = showBatchDeleteDialog,
        title = stringResource(R.string.upload_files_page_delete_selected_title, selectedIds.size),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            scope.launch {
                val ids = selectedIds.toList()
                var failed = 0
                ids.forEach { id ->
                    val ok = filesManager.delete(id, deleteFromDisk = true)
                    if (!ok) failed++
                }
                if (failed == 0) toaster.show(deletedToast) else toaster.show(deleteFailedToast)
                selectedIds.clear()
                inSelection = false
            }
            showBatchDeleteDialog = false
        },
        onDismiss = { showBatchDeleteDialog = false },
    ) {
        Text(stringResource(R.string.upload_files_page_selected_count, selectedIds.size))
    }

    // 图片预览
    previewImages?.let { images ->
        ImagePreviewDialog(
            images = images,
            onDismissRequest = { previewImages = null }
        )
    }
}

@Composable
private fun SortMenuItem(
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
                color = MaterialTheme.colorScheme.primary,
            )
        },
        onClick = onClick,
    )
}

@Composable
private fun UploadFileCard(
    file: ManagedFileEntity,
    fileOnDisk: File,
    inSelection: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
        onClick = if (inSelection) onToggleSelection else onOpen,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧：选择框 / 缩略图 / 文件图标
            if (inSelection) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() },
                )
            } else if (file.mimeType.startsWith("image/")) {
                AsyncImage(
                    model = fileOnDisk,
                    contentDescription = file.displayName,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Icon(
                    imageVector = HugeIcons.File02,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            // 中间：文件名 + 信息
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${file.mimeType} · ${formatBytes(file.sizeBytes)} · ${dateFormat.format(Date(file.createdAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 右侧：操作菜单 (模仿 SkillsPage / QuickMessagesPage)
            if (!inSelection) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = HugeIcons.MoreVertical,
                            contentDescription = stringResource(R.string.skills_page_more_actions),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.upload_files_page_open)) },
                            leadingIcon = {
                                Icon(HugeIcons.View, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onOpen()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Delete01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
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

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1fKB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1fMB", mb)
    val gb = mb / 1024.0
    return String.format("%.1fGB", gb)
}

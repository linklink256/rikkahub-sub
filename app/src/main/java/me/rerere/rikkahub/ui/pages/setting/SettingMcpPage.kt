package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.MessageBlocked
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Console
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Upload02
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowOverflow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.InputSchema
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.ListCard
import me.rerere.rikkahub.ui.components.ui.ReorderableSwipeableItem
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.hooks.EditState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.extendColors
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.rememberReorderableLazyListState
import org.koin.compose.koinInject

@Composable
fun SettingMcpPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mcpConfigs = settings.mcpServers
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        vm.reorderMcpServers(from.index, to.index)
    }
    val creationState = useEditState<McpServerConfig> {
        vm.addMcpServer(it)
    }
    val editState = useEditState<McpServerConfig> { newConfig ->
        vm.updateMcpServer(newConfig)
    }
    var showImportDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_mcp_page_title))
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = {
                            showImportDialog = true
                        }
                    ) {
                        Icon(HugeIcons.FileImport, null)
                    }
                    IconButton(
                        onClick = {
                            creationState.open(McpServerConfig.StreamableHTTPServer())
                        }
                    ) {
                        Icon(HugeIcons.Add01, null)
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        val mcpManager = koinInject<McpManager>()
        val status by mcpManager.syncingStatus.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val state = rememberPullToRefreshState()
        val loading = status.values.any { it == McpStatus.Connecting || it is McpStatus.Reconnecting }
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = {
                scope.launch {
                    mcpManager.syncAll()
                }
            },
            state = state,
            modifier = Modifier.padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(16.dp),
                state = lazyListState,
            ) {
                items(mcpConfigs, key = { it.id }) { mcpConfig ->
                    ReorderableSwipeableItem(
                        onDelete = {
                            vm.deleteMcpServer(mcpConfig)
                        },
                        state = reorderableState,
                        key = mcpConfig.id,
                        modifier = Modifier.animateItem(),
                    ) {
                        McpServerItem(
                            item = mcpConfig,
                            onEdit = { editState.open(mcpConfig) },
                        )
                    }
                }
            }

            if (mcpConfigs.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = stringResource(R.string.setting_mcp_page_no_mcp_servers_found))
                    Text(
                        text = stringResource(R.string.setting_mcp_page_add_one_to_get_started),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    McpServerConfigModal(creationState)
    McpServerConfigModal(editState)
    if (showImportDialog) {
        McpImportModal(
            onDismiss = { showImportDialog = false },
            onImport = { newConfigs ->
                vm.importMcpServers(newConfigs)
                showImportDialog = false
            }
        )
    }
}

@Composable
private fun McpServerItem(
    item: McpServerConfig,
    onEdit: (McpServerConfig) -> Unit,
) {
    val mcpManager = koinInject<McpManager>()
    val status by mcpManager.getStatus(item).collectAsStateWithLifecycle(McpStatus.Idle)
    ListCard(
        onClick = { onEdit(item) },
        leading = {
            when (status) {
                McpStatus.Idle -> Icon(HugeIcons.MessageBlocked, null)
                McpStatus.Connecting -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp)
                )
                McpStatus.Connected -> Icon(HugeIcons.McpServer, null)
                is McpStatus.Reconnecting -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp)
                )
                is McpStatus.Error -> Icon(HugeIcons.AlertCircle, null)
            }
        },
        title = item.commonOptions.name,
        titleEnd = {
            val dotColor =
                if (item.commonOptions.enable) MaterialTheme.extendColors.green6 else MaterialTheme.extendColors.red6
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .drawWithContent {
                        drawCircle(color = dotColor)
                    }
            )
        },
        tags = {
            Tag(type = TagType.SUCCESS) {
                when (item) {
                    is McpServerConfig.SseTransportServer -> Text("SSE")
                    is McpServerConfig.StreamableHTTPServer -> Text("Streamable HTTP")
                }
            }
            if (status is McpStatus.Error) {
                Tag(type = TagType.ERROR) {
                    Text(
                        text = (status as McpStatus.Error).message,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    )
}

@Composable
private fun McpServerConfigModal(state: EditState<McpServerConfig>) {
    state.EditStateContent { config, updateValue ->
        val pagerState = rememberPagerState { 2 }
        val scope = rememberCoroutineScope()
        ModalBottomSheet(
            onDismissRequest = {
                state.dismiss()
            },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.Transparent
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(0)
                            }
                        },
                        text = {
                            Text(stringResource(R.string.setting_mcp_page_basic_settings))
                        }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                        },
                        text = {
                            Text(stringResource(R.string.setting_mcp_page_tools))
                        }
                    )
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    when (page) {
                        0 -> {
                            McpCommonOptionsConfigure(
                                config = config,
                                update = updateValue
                            )
                        }

                        1 -> {
                            McpToolsConfigure(
                                config = config,
                                update = updateValue,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = {
                            if (config.commonOptions.name.isNotBlank() && isValidMcpName(config.commonOptions.name)) {
                                state.confirm()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.setting_mcp_page_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun McpCommonOptionsConfigure(
    config: McpServerConfig,
    update: (McpServerConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 启用/禁用开关
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_enable))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_enable_desc))
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.setting_mcp_page_enable))
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = config.commonOptions.enable,
                    onCheckedChange = { enabled ->
                        update(
                            when (config) {
                                is McpServerConfig.SseTransportServer -> config.copy(
                                    commonOptions = config.commonOptions.copy(enable = enabled)
                                )

                                is McpServerConfig.StreamableHTTPServer -> config.copy(
                                    commonOptions = config.commonOptions.copy(enable = enabled)
                                )
                            }
                        )
                    }
                )
            }
        }

        // 名称输入框
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_name))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_name_desc))
            }
        ) {
            val nameInvalid = !isValidMcpName(config.commonOptions.name)
            OutlinedTextField(
                value = config.commonOptions.name,
                onValueChange = { name ->
                    update(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> config.copy(
                                commonOptions = config.commonOptions.copy(name = name)
                            )

                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                commonOptions = config.commonOptions.copy(name = name)
                            )
                        }
                    )
                },
                label = { Text(stringResource(R.string.setting_mcp_page_name)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.setting_mcp_page_name_placeholder)) },
                isError = nameInvalid,
                supportingText = if (nameInvalid) {
                    { Text(stringResource(R.string.setting_mcp_page_name_invalid)) }
                } else null
            )
        }

        // 传输类型选择
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_transport_type))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_transport_type_desc))
            }
        ) {
            val transportTypes = listOf(
                "Streamable HTTP",
                "SSE"
            )
            val currentTypeIndex = when (config) {
                is McpServerConfig.StreamableHTTPServer -> 0
                is McpServerConfig.SseTransportServer -> 1
            }

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                transportTypes.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index, transportTypes.size),
                        onClick = {
                            if (index != currentTypeIndex) {
                                val newConfig = when (index) {
                                    0 -> McpServerConfig.StreamableHTTPServer(
                                        id = config.id,
                                        commonOptions = config.commonOptions,
                                        url = when (config) {
                                            is McpServerConfig.SseTransportServer -> config.url
                                            is McpServerConfig.StreamableHTTPServer -> config.url
                                        }
                                    )

                                    1 -> McpServerConfig.SseTransportServer(
                                        id = config.id,
                                        commonOptions = config.commonOptions,
                                        url = when (config) {
                                            is McpServerConfig.SseTransportServer -> config.url
                                            is McpServerConfig.StreamableHTTPServer -> config.url
                                        }
                                    )

                                    else -> config
                                }
                                update(newConfig)
                            }
                        },
                        selected = index == currentTypeIndex
                    ) {
                        Text(type)
                    }
                }
            }
        }

        // 服务器地址配置
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_server_url))
            },
            description = {
                Text(
                    when (config) {
                        is McpServerConfig.SseTransportServer -> stringResource(R.string.setting_mcp_page_sse_url_desc)
                        is McpServerConfig.StreamableHTTPServer -> stringResource(R.string.setting_mcp_page_streamable_http_url_desc)
                    }
                )
            }
        ) {
            OutlinedTextField(
                value = when (config) {
                    is McpServerConfig.SseTransportServer -> config.url
                    is McpServerConfig.StreamableHTTPServer -> config.url
                },
                onValueChange = { url ->
                    update(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> config.copy(url = url)
                            is McpServerConfig.StreamableHTTPServer -> config.copy(url = url)
                        }
                    )
                },
                label = { Text(stringResource(R.string.setting_mcp_page_url_label)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> stringResource(R.string.setting_mcp_page_sse_url_placeholder)
                            is McpServerConfig.StreamableHTTPServer -> stringResource(R.string.setting_mcp_page_streamable_http_url_placeholder)
                        }
                    )
                }
            )
        }

        // 请求头配置
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_custom_headers))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_custom_headers_desc))
            }
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                config.commonOptions.headers.forEachIndexed { index, header ->
                    var headerName by remember(header.first) { mutableStateOf(header.first) }
                    var headerValue by remember(header.second) { mutableStateOf(header.second) }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = headerName,
                                onValueChange = {
                                    headerName = it
                                    val updatedHeaders =
                                        config.commonOptions.headers.toMutableList()
                                    updatedHeaders[index] =
                                        it.trim() to updatedHeaders[index].second
                                    update(
                                        when (config) {
                                            is McpServerConfig.SseTransportServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )

                                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )
                                        }
                                    )
                                },
                                label = { Text(stringResource(R.string.setting_mcp_page_header_name)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.setting_mcp_page_header_name_placeholder)) }
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = headerValue,
                                onValueChange = {
                                    headerValue = it
                                    val updatedHeaders =
                                        config.commonOptions.headers.toMutableList()
                                    updatedHeaders[index] = updatedHeaders[index].first to it.trim()
                                    update(
                                        when (config) {
                                            is McpServerConfig.SseTransportServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )

                                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )
                                        }
                                    )
                                },
                                label = { Text(stringResource(R.string.setting_mcp_page_header_value)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.setting_mcp_page_header_value_placeholder)) }
                            )
                        }
                        IconButton(onClick = {
                            val updatedHeaders = config.commonOptions.headers.toMutableList()
                            updatedHeaders.removeAt(index)
                            update(
                                when (config) {
                                    is McpServerConfig.SseTransportServer -> config.copy(
                                        commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                    )

                                    is McpServerConfig.StreamableHTTPServer -> config.copy(
                                        commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                    )
                                }
                            )
                        }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.setting_mcp_page_delete_header)
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        val updatedHeaders = config.commonOptions.headers.toMutableList()
                        updatedHeaders.add("" to "")
                        update(
                            when (config) {
                                is McpServerConfig.SseTransportServer -> config.copy(
                                    commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                )

                                is McpServerConfig.StreamableHTTPServer -> config.copy(
                                    commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                )
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        HugeIcons.Add01,
                        contentDescription = stringResource(R.string.setting_mcp_page_add_header)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.setting_mcp_page_add_header))
                }
            }
        }
    }
}

@Composable
private fun McpToolsConfigure(
    config: McpServerConfig,
    update: (McpServerConfig) -> Unit,
) {
    val mcpManager = koinInject<McpManager>()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (mcpManager.getClient(config) == null) {
            item {
                Text(stringResource(R.string.setting_mcp_page_tools_unavailable_message))
            }
        }
        items(config.commonOptions.tools) { tool ->
            McpToolCard(
                tool = tool,
                onEnableChange = { newVal ->
                    update(
                        config.clone(
                            commonOptions = config.commonOptions.copy(
                                tools = config.commonOptions.tools.map {
                                    if (tool.name == it.name) {
                                        it.copy(enable = newVal)
                                    } else {
                                        it
                                    }
                                }
                            )
                        )
                    )
                },
                onNeedsApprovalChange = { newVal ->
                    update(
                        config.clone(
                            commonOptions = config.commonOptions.copy(
                                tools = config.commonOptions.tools.map {
                                    if (tool.name == it.name) {
                                        it.copy(needsApproval = newVal)
                                    } else {
                                        it
                                    }
                                }
                            )
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun McpToolCard(
    tool: McpTool,
    onEnableChange: (Boolean) -> Unit,
    onNeedsApprovalChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 第一行：工具名字和3个按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 需要审批开关
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_mcp_page_needs_approval),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Switch(
                        checked = tool.needsApproval,
                        onCheckedChange = onNeedsApprovalChange
                    )
                }
                // 启用开关
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "启用",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Switch(
                        checked = tool.enable,
                        onCheckedChange = onEnableChange
                    )
                }
                // 展开/收起按钮
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            // 展开后显示描述和参数
            if (expanded) {
                // 描述
                if (!tool.description.isNullOrBlank()) {
                    Text(
                        text = tool.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
                // 参数标签
                tool.inputSchema?.let { it as? InputSchema.Obj }?.let { schema ->
                    if (schema.properties.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            schema.properties.forEach { (key, _) ->
                                Tag(
                                    type = if (schema.required?.contains(key) == true) TagType.INFO else TagType.DEFAULT
                                ) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isValidMcpName(name: String): Boolean {
    return name.isEmpty() || name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
}

private fun parseMcpServersFromJson(json: String): List<McpServerConfig> {
    val root = Json.parseToJsonElement(json).jsonObject
    val mcpServers = root["mcpServers"]?.jsonObject ?: return emptyList()
    return mcpServers.entries.mapNotNull { (name, element) ->
        val obj = element.jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "streamable_http"
        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val headers = obj["headers"]?.jsonObject?.entries?.map { (k, v) ->
            k to (v.jsonPrimitive.contentOrNull ?: "")
        } ?: emptyList()
        val commonOptions = McpCommonOptions(name = name, headers = headers)
        when (type) {
            "sse" -> McpServerConfig.SseTransportServer(commonOptions = commonOptions, url = url)
            else -> McpServerConfig.StreamableHTTPServer(commonOptions = commonOptions, url = url)
        }
    }
}

@Composable
private fun McpImportModal(
    onDismiss: () -> Unit,
    onImport: (List<McpServerConfig>) -> Unit,
) {
    var jsonText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val noValidConfigMsg = stringResource(R.string.setting_mcp_page_import_no_valid_config)
    val parseErrorMsg = stringResource(R.string.setting_mcp_page_import_parse_error)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.setting_mcp_page_import_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.setting_mcp_page_import_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = jsonText,
                onValueChange = {
                    jsonText = it
                    errorMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text("{ \"mcpServers\": { ... } }") },
                isError = errorMessage != null,
                supportingText = errorMessage?.let { msg -> { Text(msg, color = MaterialTheme.colorScheme.error) } }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        try {
                            val configs = parseMcpServersFromJson(jsonText.trim())
                            if (configs.isEmpty()) {
                                errorMessage = noValidConfigMsg
                            } else {
                                onImport(configs)
                            }
                        } catch (e: Exception) {
                            errorMessage = parseErrorMsg.format(e.message ?: "")
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_mcp_page_import_confirm))
                }
            }
        }
    }
}

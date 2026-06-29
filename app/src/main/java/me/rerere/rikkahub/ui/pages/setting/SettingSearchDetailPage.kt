package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.highlight.LocalHighlighter
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeVisualTransformation
import me.rerere.rikkahub.ui.components.richtext.rememberHighlightCodeVisualTransformation
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.SectionHeader
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.plus
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchResult
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingSearchDetailPage(
    serviceId: Uuid,
    vm: SettingVM = koinViewModel()
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val nav = LocalNavController.current

    val service = settings.searchServices.find { it.id == serviceId } ?: return
    val serviceIndex = settings.searchServices.indexOf(service)
    var options by remember(service) { mutableStateOf(service) }

    fun save(updated: SearchServiceOptions) {
        options = updated
        val newServices = settings.searchServices.toMutableList()
        newServices[serviceIndex] = updated
        vm.updateSettings(settings.copy(searchServices = newServices))
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(options.displayName)
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = {
                            val newServices = settings.searchServices.filter { it.id != service.id }
                            vm.updateSettings(settings.copy(searchServices = newServices))
                            nav.popBackStack()
                        }
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.delete)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item("config") {
                Column(
                    modifier = Modifier
                        .animateContentSize()
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SectionHeader(stringResource(R.string.setting_page_search_config))
                    SearchServiceOptionsEditor(
                        options = options,
                        onUpdateOptions = { save(it) }
                    )

                    ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                        SearchService.getService(options).Description()
                    }
                }
            }

            item("test") {
                SearchTestSection(
                    options = options,
                    commonOptions = settings.searchCommonOptions
                )
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun SearchServiceOptionsEditor(
    options: SearchServiceOptions,
    onUpdateOptions: (SearchServiceOptions) -> Unit
) {
    when (options) {
        is SearchServiceOptions.TavilyOptions -> {
            TavilyOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.ExaOptions -> {
            ExaOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.ZhipuOptions -> {
            ZhipuOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.SearXNGOptions -> {
            SearXNGOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.LinkUpOptions -> {
            SearchLinkUpOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.BraveOptions -> {
            BraveOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.MetasoOptions -> {
            MetasoOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.OllamaOptions -> {
            OllamaOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.PerplexityOptions -> {
            PerplexityOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.BingLocalOptions -> {}
        is SearchServiceOptions.FirecrawlOptions -> {
            FirecrawlOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.JinaOptions -> {
            JinaOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.BochaOptions -> {
            BochaOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.RikkaHubOptions -> {
            RikkaHubOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.GrokOptions -> {
            GrokOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.TinyfishOptions -> {
            TinyfishOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.SerperOptions -> {
            SerperOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.CustomJsOptions -> {
            CustomJsOptions(options) { onUpdateOptions(it) }
        }
    }
}

@Composable
private fun SearchTestSection(
    options: SearchServiceOptions,
    commonOptions: SearchCommonOptions
) {
    var query by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<SearchResult>?>(null) }
    val scope = rememberCoroutineScope()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_page_search_test),
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.setting_page_search_test_query_hint)) },
                    singleLine = true
                )

                IconButton(
                    onClick = {
                        if (query.isNotBlank() && !testing) {
                            testing = true
                            result = null
                            scope.launch {
                                val service = SearchService.getService(options)
                                val params = JsonObject(
                                    mapOf("query" to JsonPrimitive(query))
                                )
                                result = service.search(params, commonOptions, options)
                                testing = false
                            }
                        }
                    },
                    enabled = query.isNotBlank() && !testing
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(4.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = HugeIcons.Play,
                            contentDescription = stringResource(R.string.setting_page_search_test_run)
                        )
                    }
                }
            }

            result?.let { res ->
                res.onSuccess { searchResult ->
                    searchResult.answer?.let { answer ->
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    searchResult.items.forEachIndexed { index, item ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${index + 1}. ${item.title}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = item.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = item.text.take(200),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                res.onFailure { error ->
                    Text(
                        text = error.message ?: stringResource(R.string.search_detail_unknown_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ApiKeyOnlyEditor(
    apiKey: String,
    onApiKeyChange: (String) -> Unit
) {
    TextFieldFormItem(
        label = stringResource(R.string.search_detail_api_key),
        value = apiKey,
        onValueChange = onApiKeyChange
    )
}

@Composable
private fun DepthSegmentedSelector(
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_depth))
        }
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEachIndexed { index, depth ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    onClick = { onSelect(depth) },
                    selected = selected == depth
                ) {
                    Text(depth.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@Composable
private fun TextFieldFormItem(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    FormItem(
        label = {
            Text(label)
        }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder?.let { { Text(it) } },
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
        )
    }
}

@Composable
internal fun TavilyOptions(
    options: SearchServiceOptions.TavilyOptions,
    onUpdateOptions: (SearchServiceOptions.TavilyOptions) -> Unit
) {
    ApiKeyOnlyEditor(
        apiKey = options.apiKey,
        onApiKeyChange = { onUpdateOptions(options.copy(apiKey = it)) }
    )
    DepthSegmentedSelector(
        selected = options.depth,
        options = listOf("basic", "advanced"),
        onSelect = { onUpdateOptions(options.copy(depth = it)) }
    )
}

@Composable
internal fun ExaOptions(
    options: SearchServiceOptions.ExaOptions,
    onUpdateOptions: (SearchServiceOptions.ExaOptions) -> Unit
) {
    ApiKeyOnlyEditor(
        apiKey = options.apiKey,
        onApiKeyChange = { onUpdateOptions(options.copy(apiKey = it)) }
    )
}

@Composable
internal fun ZhipuOptions(
    options: SearchServiceOptions.ZhipuOptions,
    onUpdateOptions: (SearchServiceOptions.ZhipuOptions) -> Unit
) {
    ApiKeyOnlyEditor(
        apiKey = options.apiKey,
        onApiKeyChange = { onUpdateOptions(options.copy(apiKey = it)) }
    )
}

@Composable
internal fun SearXNGOptions(
    options: SearchServiceOptions.SearXNGOptions,
    onUpdateOptions: (SearchServiceOptions.SearXNGOptions) -> Unit
) {
    TextFieldFormItem(
        label = stringResource(R.string.search_detail_api_url),
        value = options.url,
        onValueChange = { onUpdateOptions(options.copy(url = it)) }
    )
    TextFieldFormItem(
        label = stringResource(R.string.search_detail_engines),
        value = options.engines,
        onValueChange = { onUpdateOptions(options.copy(engines = it)) }
    )
    TextFieldFormItem(
        label = stringResource(R.string.search_detail_language),
        value = options.language,
        onValueChange = { onUpdateOptions(options.copy(language = it)) }
    )
    TextFieldFormItem(
        label = stringResource(R.string.search_detail_username),
        value = options.username,
        onValueChange = { onUpdateOptions(options.copy(username = it)) }
    )
    TextFieldFormItem(
        label = stringResource(R.string.search_detail_password),
        value = options.password,
        onValueChange = { onUpdateOptions(options.copy(password = it)) }
    )
}

@Composable
internal fun SearchLinkUpOptions(
    options: SearchServiceOptions.LinkUpOptions,
    onUpdateOptions: (SearchServiceOptions.LinkUpOptions) -> Unit
) {
    ApiKeyOnlyEditor(
        apiKey = options.apiKey,
        onApiKeyChange = { onUpdateOptions(options.copy(apiKey = it)) }
    )
    DepthSegmentedSelector(
        selected = options.depth,
        options = listOf("standard", "deep"),
        onSelect = { onUpdateOptions(options.copy(depth = it)) }
    )
}

@Composable
internal fun BraveOptions(
    options: SearchServiceOptions.BraveOptions,
    onUpdateOptions: (SearchServiceOptions.BraveOptions) -> Unit
) {
    ApiKeyOnlyEditor(
        apiKey = options.apiKey,
        onApiKeyChange = { onUpdateOptions(options.copy(apiKey = it)) }
    )
}

@Composable
internal fun SerperOptions(
    options: SearchServiceOptions.SerperOptions,
    onUpdateOptions: (SearchServiceOptions.SerperOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_key))
        }
    ) {
        OutlinedTextField(
            value = options.apiKey,
            onValueChange = {
                onUpdateOptions(options.copy(apiKey = it))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun MetasoOptions(
    options: SearchServiceOptions.MetasoOptions,
    onUpdateOptions: (SearchServiceOptions.MetasoOptions) -> Unit
) {
    ApiKeyOnlyEditor(
        apiKey = options.apiKey,
        onApiKeyChange = { onUpdateOptions(options.copy(apiKey = it)) }
    )
}

@Composable
internal fun OllamaOptions(
    options: SearchServiceOptions.OllamaOptions,
    onUpdateOptions: (SearchServiceOptions.OllamaOptions) -> Unit
) {
    ApiKeyOnlyEditor(
        apiKey = options.apiKey,
        onApiKeyChange = { onUpdateOptions(options.copy(apiKey = it)) }
    )
}

@Composable
internal fun PerplexityOptions(
    options: SearchServiceOptions.PerplexityOptions,
    onUpdateOptions: (SearchServiceOptions.PerplexityOptions) -> Unit
) {
    ApiKeyOnlyEditor(
        apiKey = options.apiKey,
        onApiKeyChange = { onUpdateOptions(options.copy(apiKey = it)) }
    )
    TextFieldFormItem(
        label = stringResource(R.string.search_detail_max_tokens),
        value = options.maxTokens?.takeIf { it > 0 }?.toString() ?: "",
        onValueChange = { value -> onUpdateOptions(options.copy(maxTokens = value.toIntOrNull())) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
    TextFieldFormItem(
        label = stringResource(R.string.search_detail_max_tokens_per_page),
        value = options.maxTokensPerPage?.takeIf { it > 0 }?.toString() ?: "",
        onValueChange = { value -> onUpdateOptions(options.copy(maxTokensPerPage = value.toIntOrNull())) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
internal fun FirecrawlOptions(
    options: SearchServiceOptions.FirecrawlOptions,
    onUpdateOptions: (SearchServiceOptions.FirecrawlOptions) -> Unit
) {
    ApiKeyOnlyEditor(
        apiKey = options.apiKey,
        onApiKeyChange = { onUpdateOptions(options.copy(apiKey = it)) }
    )
}

@Composable
internal fun JinaOptions(
    options: SearchServiceOptions.JinaOptions,
    onUpdateOptions: (SearchServiceOptions.JinaOptions) -> Unit
) {
    ApiKeyOnlyEditor(
        apiKey = options.apiKey,
        onApiKeyChange = { onUpdateOptions(options.copy(apiKey = it)) }
    )
    TextFieldFormItem(
        label = stringResource(R.string.search_detail_search_url),
        value = options.searchUrl,
        onValueChange = { onUpdateOptions(options.copy(searchUrl = it.trim())) },
        placeholder = "https://s.jina.ai/"
    )
    TextFieldFormItem(
        label = stringResource(R.string.search_detail_scrape_url),
        value = options.scrapeUrl,
        onValueChange = { onUpdateOptions(options.copy(scrapeUrl = it.trim())) },
        placeholder = "https://r.jina.ai/"
    )
}

@Composable
internal fun BochaOptions(
    options: SearchServiceOptions.BochaOptions,
    onUpdateOptions: (SearchServiceOptions.BochaOptions) -> Unit
) {
    ApiKeyOnlyEditor(
        apiKey = options.apiKey,
        onApiKeyChange = { onUpdateOptions(options.copy(apiKey = it)) }
    )
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_summary))
        },
        description = {
            Text(stringResource(R.string.search_detail_summary_desc))
        },
        tail = {
            Switch(
                checked = options.summary,
                onCheckedChange = { checked ->
                    onUpdateOptions(options.copy(summary = checked))
                }
            )
        }
    )
}

@Composable
internal fun RikkaHubOptions(
    options: SearchServiceOptions.RikkaHubOptions,
    onUpdateOptions: (SearchServiceOptions.RikkaHubOptions) -> Unit
) {
    ApiKeyOnlyEditor(
        apiKey = options.apiKey,
        onApiKeyChange = { onUpdateOptions(options.copy(apiKey = it)) }
    )
    DepthSegmentedSelector(
        selected = options.depth,
        options = listOf("standard", "deep"),
        onSelect = { onUpdateOptions(options.copy(depth = it)) }
    )
}

@Composable
internal fun TinyfishOptions(
    options: SearchServiceOptions.TinyfishOptions,
    onUpdateOptions: (SearchServiceOptions.TinyfishOptions) -> Unit
) {
    ApiKeyOnlyEditor(
        apiKey = options.apiKey,
        onApiKeyChange = { onUpdateOptions(options.copy(apiKey = it)) }
    )
}

@Composable
internal fun GrokOptions(
    options: SearchServiceOptions.GrokOptions,
    onUpdateOptions: (SearchServiceOptions.GrokOptions) -> Unit
) {
    ApiKeyOnlyEditor(
        apiKey = options.apiKey,
        onApiKeyChange = { onUpdateOptions(options.copy(apiKey = it)) }
    )
    TextFieldFormItem(
        label = stringResource(R.string.search_detail_model),
        value = options.model,
        onValueChange = { onUpdateOptions(options.copy(model = it)) }
    )
    TextFieldFormItem(
        label = stringResource(R.string.search_detail_custom_url),
        value = options.customUrl,
        onValueChange = { onUpdateOptions(options.copy(customUrl = it)) }
    )
    TextFieldFormItem(
        label = stringResource(R.string.search_detail_system_prompt),
        value = options.systemPrompt,
        onValueChange = { onUpdateOptions(options.copy(systemPrompt = it)) },
        minLines = 3
    )
}

@Composable
internal fun CustomJsOptions(
    options: SearchServiceOptions.CustomJsOptions,
    onUpdateOptions: (SearchServiceOptions.CustomJsOptions) -> Unit
) {
    TextFieldFormItem(
        label = stringResource(R.string.search_detail_name),
        value = options.name,
        onValueChange = { onUpdateOptions(options.copy(name = it)) },
        placeholder = stringResource(R.string.search_detail_custom_search_placeholder)
    )

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_search_script))
        }
    ) {
        val highlighter = LocalHighlighter.current
        val darkMode = LocalDarkMode.current
        OutlinedTextField(
            value = options.searchScript,
            onValueChange = {
                onUpdateOptions(options.copy(searchScript = it))
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 8,
            maxLines = 20,
            visualTransformation = rememberHighlightCodeVisualTransformation(
                language = "javascript",
                highlighter = highlighter,
                darkMode = darkMode,
                text = options.searchScript,
            ),
            textStyle = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_scrape_script))
        },
        description = {
            Text(stringResource(R.string.search_detail_scrape_script_desc))
        }
    ) {
        val highlighter = LocalHighlighter.current
        val darkMode = LocalDarkMode.current
        OutlinedTextField(
            value = options.scrapeScript,
            onValueChange = {
                onUpdateOptions(options.copy(scrapeScript = it))
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 20,
            placeholder = {
                Text(
                    text = SearchServiceOptions.CustomJsOptions.DEFAULT_SCRAPE_SCRIPT.trimIndent(),
                    style = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            },
            visualTransformation = rememberHighlightCodeVisualTransformation(
                language = "javascript",
                highlighter = highlighter,
                darkMode = darkMode,
                text = options.scrapeScript,
            ),
            textStyle = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
        )
    }
}

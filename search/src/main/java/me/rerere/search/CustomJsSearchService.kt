package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.common.js.injectFetch
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json

object CustomJsSearchService : SearchService<SearchServiceOptions.CustomJsOptions> {
    override val name: String = "Custom JS"

    @Composable
    override fun Description() {
        Text(stringResource(R.string.custom_js_desc))
    }

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.CustomJsOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params.requireQuery()
            val script = serviceOptions.searchScript.ifBlank { error("Search script is empty") }

            val resultJson = executeScript(
                userScript = script,
                invocation = "search(${json.encodeToString(String.serializer(), query)}, ${commonOptions.resultSize})"
            )

            json.decodeFromString<SearchResult>(resultJson)
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.CustomJsOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val script = serviceOptions.scrapeScript.ifBlank { error("Scrape script is empty") }
            val urlsJson = params["urls"]?.toString() ?: error("urls is required")

            val resultJson = executeScript(
                userScript = script,
                invocation = "scrape($urlsJson)"
            )

            json.decodeFromString<ScrapedResult>(resultJson)
        }
    }

    private fun executeScript(userScript: String, invocation: String): String {
        val context = QuickJSContext.create()
        try {
            context.injectFetch(httpClient)
            context.evaluate(userScript)

            val result = context.evaluate("JSON.stringify($invocation)")
            return result as? String ?: error("Function returned null or undefined")
        } finally {
            context.destroy()
        }
    }
}
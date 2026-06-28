package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.json
import okhttp3.Response

private const val TAG = "OllamaSearchService"

object OllamaSearchService : HttpSearchService<SearchServiceOptions.OllamaOptions>() {
    override val name: String = "Ollama"

    override val baseUrl: String = "https://ollama.com/api/web_search"

    @Composable
    override fun Description() = ApiKeyButton("https://ollama.com/settings/keys")

    override fun buildRequestBody(
        query: String,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.OllamaOptions
    ): String? = buildJsonObject {
        put("query", query)
        put("max_results", commonOptions.resultSize.coerceIn(5..10))
    }.toString()

    override fun validateResponse(response: Response) {
        if (!response.isSuccessful) {
            error("Ollama search failed with code ${response.code}: ${response.message}")
        }
    }

    override fun parseSearchResponse(raw: String, commonOptions: SearchCommonOptions): SearchResult {
        val searchResponse = json.decodeFromString<OllamaSearchResponse>(raw)
        return SearchResult(
            items = searchResponse.results.map {
                SearchResultItem(
                    title = it.title,
                    url = it.url,
                    text = it.content
                )
            }
        )
    }

    override fun extractApiKey(serviceOptions: SearchServiceOptions.OllamaOptions): String = serviceOptions.apiKey

    @Serializable
    private data class OllamaSearchResponse(
        val results: List<OllamaSearchResult>
    )

    @Serializable
    private data class OllamaSearchResult(
        val title: String,
        val url: String,
        val content: String
    )
}

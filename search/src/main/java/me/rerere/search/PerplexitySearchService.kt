package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.json
import okhttp3.Response

private const val PERPLEXITY_ENDPOINT = "https://api.perplexity.ai/search"

object PerplexitySearchService : HttpSearchService<SearchServiceOptions.PerplexityOptions>() {
    override val name: String = "Perplexity"

    override val baseUrl: String = PERPLEXITY_ENDPOINT

    @Composable
    override fun Description() = ApiKeyButton("https://www.perplexity.ai/settings/api")

    override fun buildRequestBody(
        query: String,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.PerplexityOptions
    ): String? = buildJsonObject {
        put("query", JsonPrimitive(query))
        put("max_results", JsonPrimitive(commonOptions.resultSize))
        serviceOptions.maxTokens?.let {
            if (it > 0) {
                put("max_tokens", JsonPrimitive(it))
            }
        }
        serviceOptions.maxTokensPerPage?.let {
            if (it > 0) {
                put("max_tokens_per_page", JsonPrimitive(it))
            }
        }
    }.toString()

    override fun validateResponse(response: Response) {
        if (!response.isSuccessful) {
            error("response failed #${response.code}: ${response.body?.string()}")
        }
    }

    override fun parseSearchResponse(raw: String): SearchResult {
        val responseBody = json.decodeFromString<PerplexityResponse>(raw)
        val items = responseBody.results
            .filter { !it.title.isNullOrBlank() && !it.url.isNullOrBlank() }
            .map {
                SearchResultItem(
                    title = it.title!!,
                    url = it.url!!,
                    text = it.snippet ?: it.text ?: ""
                )
            }
        return SearchResult(
            answer = responseBody.answer,
            items = items
        )
    }

    override fun extractApiKey(serviceOptions: SearchServiceOptions.PerplexityOptions): String {
        if (serviceOptions.apiKey.isBlank()) {
            error("Perplexity API key is required")
        }
        return serviceOptions.apiKey
    }

    @Serializable
    private data class PerplexityResponse(
        val answer: String? = null,
        val results: List<ResultItem> = emptyList()
    ) {
        @Serializable
        data class ResultItem(
            val title: String? = null,
            val url: String? = null,
            val snippet: String? = null,
            @SerialName("text") val text: String? = null,
        )
    }
}

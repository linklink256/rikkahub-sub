package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.json
import okhttp3.Response

private const val TAG = "BraveSearchService"

object BraveSearchService : HttpSearchService<SearchServiceOptions.BraveOptions>() {
    override val name: String = "Brave"

    override val httpMethod: String = "GET"
    override val authHeaderName: String = "X-Subscription-Token"
    override val authHeaderPrefix: String = ""

    @Composable
    override fun Description() = ApiKeyButton("https://api.search.brave.com/")

    override fun buildUrl(
        query: String,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BraveOptions
    ): String = "https://api.search.brave.com/res/v1/web/search" +
            "?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
            "&count=${commonOptions.resultSize}"

    override fun extraHeaders(serviceOptions: SearchServiceOptions.BraveOptions): Map<String, String> =
        mapOf("Accept" to "application/json")

    override fun validateResponse(response: Response) {
        if (!response.isSuccessful) {
            error("Brave search failed with code ${response.code}: ${response.message}")
        }
    }

    override fun parseSearchResponse(raw: String): SearchResult {
        val searchResponse = json.decodeFromString<BraveSearchResponse>(raw)
        val items = searchResponse.web?.results?.map { result ->
            SearchResultItem(
                title = result.title,
                url = result.url,
                text = result.description ?: ""
            )
        } ?: emptyList()

        return SearchResult(
            answer = null,
            items = items
        )
    }

    override fun extractApiKey(serviceOptions: SearchServiceOptions.BraveOptions): String = serviceOptions.apiKey

    @Serializable
    data class BraveSearchResponse(
        val type: String? = null,
        val web: WebResults? = null,
    )

    @Serializable
    data class WebResults(
        val type: String? = null,
        val results: List<WebResult>? = null,
    )

    @Serializable
    data class WebResult(
        val type: String,
        val title: String,
        val url: String,
        val description: String? = null,
    )
}

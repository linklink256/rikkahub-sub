package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.json
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder

private const val TAG = "SearXNGService"

object SearXNGService : HttpSearchService<SearchServiceOptions.SearXNGOptions>() {
    override val name: String = "SearXNG"

    override val httpMethod: String = "GET"
    override val useKeyRoulette: Boolean = false

    @Composable
    override fun Description() {
        Text(stringResource(R.string.searxng_desc_1))
        Text(stringResource(R.string.searxng_desc_2))
    }

    override fun buildUrl(
        query: String,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SearXNGOptions
    ): String {
        require(serviceOptions.url.isNotBlank()) {
            "SearXNG URL cannot be empty"
        }

        val baseUrl = serviceOptions.url.trimEnd('/')
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return "$baseUrl/search?q=$encodedQuery&format=json"
            .toHttpUrl()
            .newBuilder()
            .apply {
                if (serviceOptions.engines.isNotBlank()) {
                    addQueryParameter("engines", serviceOptions.engines)
                }
                if (serviceOptions.language.isNotBlank()) {
                    addQueryParameter("language", serviceOptions.language)
                }
            }
            .build()
            .toString()
    }

    override fun extraHeaders(serviceOptions: SearchServiceOptions.SearXNGOptions): Map<String, String> {
        return if (serviceOptions.username.isNotBlank() && serviceOptions.password.isNotBlank()) {
            mapOf("Authorization" to Credentials.basic(serviceOptions.username, serviceOptions.password))
        } else {
            emptyMap()
        }
    }

    override fun validateResponse(response: okhttp3.Response) {
        if (!response.isSuccessful) {
            error("SearXNG request failed with status ${response.code}")
        }
    }

    override fun parseSearchResponse(raw: String, commonOptions: SearchCommonOptions): SearchResult {
        val searchResponse = json.decodeOrThrow<SearXNGResponse>(raw)
        val items = searchResponse.results
            .take(commonOptions.resultSize)
            .map { result ->
                SearchResultItem(
                    title = result.title,
                    url = result.url,
                    text = result.content
                )
            }
        return SearchResult(items = items)
    }

    override fun extractApiKey(serviceOptions: SearchServiceOptions.SearXNGOptions): String = ""

    @Serializable
    data class SearXNGResponse(
        @SerialName("results")
        val results: List<SearXNGResult>,
    )

    @Serializable
    data class SearXNGResult(
        @SerialName("url")
        val url: String,
        @SerialName("title")
        val title: String,
        @SerialName("content")
        val content: String,
    )
}

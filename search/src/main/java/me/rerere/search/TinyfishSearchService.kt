package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.common.http.await
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object TinyfishSearchService : HttpSearchService<SearchServiceOptions.TinyfishOptions>() {
    override val name: String = "Tinyfish"

    override val httpMethod: String = "GET"
    override val authHeaderName: String = "X-API-Key"
    override val authHeaderPrefix: String = ""

    @Composable
    override fun Description() = ApiKeyButton("https://agent.tinyfish.ai/api-keys")

    override fun scrapingParameters(options: SearchServiceOptions.TinyfishOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "url to scrape")
                })
            },
            required = listOf("url")
        )

    override fun buildUrl(
        query: String,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.TinyfishOptions
    ): String = "https://api.search.tinyfish.ai" +
            "?query=${java.net.URLEncoder.encode(query, "UTF-8")}"

    override fun validateResponse(response: okhttp3.Response) {
        if (!response.isSuccessful) {
            error("Tinyfish search failed with code ${response.code}: ${response.message}")
        }
    }

    override fun parseSearchResponse(raw: String): SearchResult {
        val searchResponse = json.decodeFromString<TinyfishSearchResponse>(raw)
        val items = searchResponse.results.map { result ->
            SearchResultItem(
                title = result.title,
                url = result.url,
                text = result.snippet
            )
        }
        return SearchResult(
            answer = null,
            items = items
        )
    }

    override fun extractApiKey(serviceOptions: SearchServiceOptions.TinyfishOptions): String = serviceOptions.apiKey

    // ---- Preserve scrape (unchanged) ----
    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.TinyfishOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
            val body = buildJsonObject {
                put("urls", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(url))
                })
                put("format", "markdown")
            }

            val request = Request.Builder()
                .url("https://api.fetch.tinyfish.ai")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("X-API-Key", serviceOptions.apiKey)
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val responseBody = response.body.string()
                val fetchResponse = json.decodeFromString<TinyfishFetchResponse>(responseBody)

                return@withContext Result.success(
                    ScrapedResult(
                        urls = fetchResponse.results.map {
                            ScrapedResultUrl(
                                url = it.url,
                                content = it.text ?: "",
                                metadata = ScrapedResultMetadata(
                                    title = it.title,
                                    description = it.description,
                                    language = it.language,
                                )
                            )
                        }
                    )
                )
            } else {
                error("Tinyfish fetch failed with code ${response.code}: ${response.message}")
            }
        }
    }

    @Serializable
    data class TinyfishSearchResponse(
        val query: String? = null,
        val results: List<TinyfishSearchResultItem> = emptyList(),
        @SerialName("total_results")
        val totalResults: Int? = null,
        val page: Int? = null,
    )

    @Serializable
    data class TinyfishSearchResultItem(
        val position: Int? = null,
        @SerialName("site_name")
        val siteName: String? = null,
        val title: String,
        val snippet: String = "",
        val url: String,
    )

    @Serializable
    data class TinyfishFetchResponse(
        val results: List<TinyfishFetchResultItem> = emptyList(),
        val errors: List<TinyfishFetchError> = emptyList(),
    )

    @Serializable
    data class TinyfishFetchResultItem(
        val url: String,
        @SerialName("final_url")
        val finalUrl: String? = null,
        val title: String? = null,
        val description: String? = null,
        val language: String? = null,
        val text: String? = null,
    )

    @Serializable
    data class TinyfishFetchError(
        val url: String? = null,
        val error: String? = null,
    )
}

package me.rerere.search

import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.json

private const val TAG = "RikkaHubSearchService"

object RikkaHubSearchService : HttpSearchService<SearchServiceOptions.RikkaHubOptions>() {
    override val name: String = "RikkaHub"

    override val baseUrl: String = "https://api.rikka-ai.com/v1/search"

    @Composable
    override fun Description() {
    }

    override fun buildRequestBody(
        query: String,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.RikkaHubOptions
    ): String? = buildJsonObject {
        put("q", JsonPrimitive(query))
        put("depth", JsonPrimitive(serviceOptions.depth))
        put("outputType", JsonPrimitive("sourcedAnswer"))
        put("includeImages", JsonPrimitive("false"))
    }.toString()

    override fun validateResponse(response: okhttp3.Response) {
        if (!response.isSuccessful) {
            error("response failed #${response.code}: ${response.body?.string()}")
        }
    }

    override fun parseSearchResponse(raw: String): SearchResult {
        val responseBody = json.decodeFromString<RikkaHubSearchResponse>(raw)
        return SearchResult(
            answer = responseBody.answer,
            items = responseBody.sources.map {
                SearchResultItem(
                    title = it.name,
                    url = it.url,
                    text = it.snippet
                )
            }
        )
    }

    override fun extractApiKey(serviceOptions: SearchServiceOptions.RikkaHubOptions): String = serviceOptions.apiKey

    // ---- Preserve scrape (stub) ----
    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.RikkaHubOptions
    ): Result<ScrapedResult> {
        error("RikkaHub does not support scraping")
    }

    @Serializable
    data class RikkaHubSearchResponse(
        val answer: String,
        val sources: List<Source>
    )

    @Serializable
    data class Source(
        val name: String,
        val url: String,
        val snippet: String
    )
}

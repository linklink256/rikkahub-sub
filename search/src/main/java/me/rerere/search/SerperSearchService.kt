package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.json

private const val TAG = "SerperSearchService"

object SerperSearchService : HttpSearchService<SearchServiceOptions.SerperOptions>() {
    override val name: String = "Serper"

    override val baseUrl: String = "https://google.serper.dev/search"
    override val authHeaderName: String = "X-API-KEY"
    override val authHeaderPrefix: String = ""
    override val useKeyRoulette: Boolean = true

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://serper.dev/api-keys")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.SerperOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.SerperOptions): InputSchema? = null

    override fun buildRequestBody(
        query: String,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SerperOptions
    ): String? = buildJsonObject {
        put("q", query)
        put("num", commonOptions.resultSize)
    }.toString()

    override fun validateResponse(response: okhttp3.Response) {
        if (!response.isSuccessful) {
            error("Serper search failed with code ${response.code}: ${response.message}")
        }
    }

    override fun parseSearchResponse(raw: String, commonOptions: SearchCommonOptions): SearchResult {
        val searchResponse = json.decodeFromString<SerperSearchResponse>(raw)
        val answer = searchResponse.answerBox?.let { it.answer ?: it.snippet }
            ?: searchResponse.knowledgeGraph?.description
        val items = searchResponse.organic.map { result ->
            SearchResultItem(
                title = result.title,
                url = result.link,
                text = result.snippet ?: ""
            )
        }
        return SearchResult(
            answer = answer,
            items = items
        )
    }

    override fun extractApiKey(serviceOptions: SearchServiceOptions.SerperOptions): String = serviceOptions.apiKey

    // ---- Preserve scrape (stub) ----
    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SerperOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Serper"))
    }

    @Serializable
    data class SerperSearchResponse(
        val answerBox: AnswerBox? = null,
        val knowledgeGraph: KnowledgeGraph? = null,
        val organic: List<OrganicResult> = emptyList(),
    )

    @Serializable
    data class AnswerBox(
        val answer: String? = null,
        val snippet: String? = null,
        val title: String? = null,
    )

    @Serializable
    data class KnowledgeGraph(
        val title: String? = null,
        val description: String? = null,
    )

    @Serializable
    data class OrganicResult(
        val title: String,
        val link: String,
        val snippet: String? = null,
    )
}

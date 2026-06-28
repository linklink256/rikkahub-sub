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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.json

object ExaSearchService : HttpSearchService<SearchServiceOptions.ExaOptions>() {
    override val name: String = "Exa"

    override val baseUrl: String = "https://api.exa.ai/search"
    override val useKeyRoulette: Boolean = true

    @Composable
    override fun Description() = ApiKeyButton("https://dashboard.exa.ai/api-keys")

    override fun parameters(options: SearchServiceOptions.ExaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                queryField()
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "Search type: fast (quick results), auto (default, balanced), deep (synthesized answer with citations)")
                    put("enum", buildJsonArray {
                        add("fast")
                        add("auto")
                        add("deep")
                    })
                })
            },
            required = listOf("query")
        )

    override fun buildRequestBody(
        query: String,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions
    ): String? = json.encodeToString(buildJsonObject {
        put("query", JsonPrimitive(query))
        put("numResults", JsonPrimitive(commonOptions.resultSize))
        put("type", JsonPrimitive(params["type"]?.jsonPrimitive?.content ?: "auto"))
        put("contents", buildJsonObject {
            put("text", JsonPrimitive(true))
        })
    })

    override fun parseSearchResponse(raw: String): SearchResult {
        val exaResponse = json.decodeOrThrow<ExaData>(raw)
        return SearchResult(
            answer = exaResponse.output?.content,
            items = exaResponse.results.map {
                SearchResultItem(
                    title = it.title,
                    url = it.url,
                    text = it.text ?: ""
                )
            },
            images = exaResponse.results.mapNotNull { it.image?.takeIf { url -> url.isNotBlank() } },
        )
    }

    override fun extractApiKey(serviceOptions: SearchServiceOptions.ExaOptions): String = serviceOptions.apiKey

    @Serializable
    data class ExaData(
        @SerialName("requestId")
        val requestId: String? = null,
        @SerialName("autopromptString")
        val autopromptString: String? = null,
        @SerialName("resolvedSearchType")
        val resolvedSearchType: String? = null,
        @SerialName("results")
        val results: List<ExaResult>,
        @SerialName("output")
        val output: ExaOutput? = null,
    )

    @Serializable
    data class ExaOutput(
        @SerialName("content")
        val content: String? = null,
        @SerialName("grounding")
        val grounding: List<ExaGrounding> = emptyList(),
    )

    @Serializable
    data class ExaGrounding(
        @SerialName("field")
        val field: String? = null,
        @SerialName("citations")
        val citations: List<ExaCitation> = emptyList(),
        @SerialName("confidence")
        val confidence: String? = null,
    )

    @Serializable
    data class ExaCitation(
        @SerialName("url")
        val url: String,
        @SerialName("title")
        val title: String,
    )

    @Serializable
    data class ExaResult(
        @SerialName("id")
        val id: String,
        @SerialName("title")
        val title: String,
        @SerialName("url")
        val url: String,
        @SerialName("publishedDate")
        val publishedDate: String?,
        @SerialName("author")
        val author: String?,
        @SerialName("text")
        val text: String? = null,
        @SerialName("image")
        val image: String? = null,
    )
}

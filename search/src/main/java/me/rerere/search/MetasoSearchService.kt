package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.json
import okhttp3.Response

object MetasoSearchService : HttpSearchService<SearchServiceOptions.MetasoOptions>() {
    override val name: String = "Metaso"

    override val baseUrl: String = "https://metaso.cn/api/v1/search"

    @Composable
    override fun Description() {
        Text(buildAnnotatedString {
            append("秘塔搜索: ")
            withLink(LinkAnnotation.Url("https://metaso.cn/")) {
                append("https://metaso.cn/")
            }
        })
    }

    override fun buildRequestBody(
        query: String,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.MetasoOptions
    ): String? = buildJsonObject {
        put("q", JsonPrimitive(query))
        put("scope", JsonPrimitive("webpage"))
        put("size", JsonPrimitive(commonOptions.resultSize))
        put("includeSummary", JsonPrimitive(false))
    }.toString()

    override fun extraHeaders(serviceOptions: SearchServiceOptions.MetasoOptions): Map<String, String> =
        mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json"
        )

    override fun validateResponse(response: Response) {
        if (!response.isSuccessful) {
            val errorBody = response.body?.string()
            error("Search request failed with code ${response.code}: $errorBody")
        }
    }

    override fun parseSearchResponse(raw: String): SearchResult {
        val searchResponse = json.decodeOrThrow<MetasoSearchResponse>(raw)
        return SearchResult(
            items = searchResponse.webpages.map { webpage ->
                SearchResultItem(
                    title = webpage.title,
                    url = webpage.link,
                    text = webpage.snippet ?: ""
                )
            }
        )
    }

    @Serializable
    data class MetasoSearchResponse(
        @SerialName("credits")
        val credits: Int,
        @SerialName("searchParameters")
        val searchParameters: MetasoSearchParameters,
        @SerialName("webpages")
        val webpages: List<MetasoWebpage>
    )

    @Serializable
    data class MetasoSearchParameters(
        @SerialName("q")
        val query: String,
        @SerialName("scope")
        val scope: String,
        @SerialName("size")
        val size: Int,
    )

    @Serializable
    data class MetasoWebpage(
        @SerialName("title")
        val title: String,
        @SerialName("link")
        val link: String,
        @SerialName("score")
        val score: String,
        @SerialName("snippet")
        val snippet: String?,
        @SerialName("summary")
        val summary: String?,
        @SerialName("position")
        val position: Int,
        @SerialName("date")
        val date: String,
    )
}

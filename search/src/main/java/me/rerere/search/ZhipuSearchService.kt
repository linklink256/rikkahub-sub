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

object ZhipuSearchService : HttpSearchService<SearchServiceOptions.ZhipuOptions>() {
    override val name: String = "Zhipu"

    override val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4/web_search"

    @Composable
    override fun Description() = ApiKeyButton("https://bigmodel.cn/usercenter/proj-mgmt/apikeys")

    override fun buildRequestBody(
        query: String,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ZhipuOptions
    ): String? = json.encodeToString(buildJsonObject {
        put("search_query", JsonPrimitive(query))
        put("search_engine", JsonPrimitive("search_std"))
        put("count", JsonPrimitive(commonOptions.resultSize))
    })

    override fun parseSearchResponse(raw: String): SearchResult {
        val zhipuResponse = json.decodeOrThrow<ZhipuDto>(raw)
        return SearchResult(
            items = zhipuResponse.searchResult.map {
                SearchResultItem(
                    title = it.title,
                    url = it.link,
                    text = it.content,
                )
            }
        )
    }

    override fun extractApiKey(serviceOptions: SearchServiceOptions.ZhipuOptions): String = serviceOptions.apiKey

    @Serializable
    data class ZhipuDto(
        @SerialName("search_result")
        val searchResult: List<ZhipuSearchResultDto>
    )

    @Serializable
    data class ZhipuSearchResultDto(
        @SerialName("content")
        val content: String,
        @SerialName("icon")
        val icon: String?,
        @SerialName("link")
        val link: String,
        @SerialName("media")
        val media: String?,
        @SerialName("refer")
        val refer: String?,
        @SerialName("title")
        val title: String
    )
}

package me.rerere.search
import me.rerere.common.http.await

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object ZhipuSearchService : SearchService<SearchServiceOptions.ZhipuOptions> {
    override val name: String = "Zhipu"

    @Composable
    override fun Description() = ApiKeyButton("https://bigmodel.cn/usercenter/proj-mgmt/apikeys")

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ZhipuOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params.requireQuery()

            val body = buildJsonObject {
                put("search_query", JsonPrimitive(query))
                put("search_engine", JsonPrimitive("search_std"))
                put("count", JsonPrimitive(commonOptions.resultSize))
            }

            val request = Request.Builder()
                .url("https://open.bigmodel.cn/api/paas/v4/web_search")
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .build()

            val response = httpClient.newCall(request).await()
            response.requireSuccess()
            val bodyRaw = response.body?.string() ?: error("Failed to get response body")
            val zhipuResponse = json.decodeOrThrow<ZhipuDto>(bodyRaw)

            return@withContext Result.success(
                SearchResult(
                    items = zhipuResponse.searchResult.map {
                        SearchResultItem(
                            title = it.title,
                            url = it.link,
                            text = it.content,
                        )
                    }
                ))
        }
    }


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

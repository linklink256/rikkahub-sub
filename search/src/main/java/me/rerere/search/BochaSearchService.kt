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

object BochaSearchService : SearchService<SearchServiceOptions.BochaOptions> {
    override val name: String = "Bocha"

    @Composable
    override fun Description() = ApiKeyButton("https://open.bochaai.com/")

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BochaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params.requireQuery()

            val body = buildJsonObject {
                put("query", JsonPrimitive(query))
                put("summary", JsonPrimitive(serviceOptions.summary))
                put("count", JsonPrimitive(commonOptions.resultSize))
            }

            val request = Request.Builder()
                .url("https://api.bochaai.com/v1/web-search")
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).await()
            response.requireSuccess()
            val bodyRaw = response.body.string()
            val bochaResponse = json.decodeOrThrow<BochaResponse>(bodyRaw)

            if (bochaResponse.code != 200) {
                error("Bocha API error: ${bochaResponse.msg ?: "Unknown error"}")
            }

            return@withContext Result.success(
                SearchResult(
                    items = bochaResponse.data?.webPages?.value?.map {
                        SearchResultItem(
                            title = it.name,
                            url = it.url,
                            text = it.summary ?: it.snippet,
                        )
                    } ?: emptyList()
                )
            )
        }
    }


    @Serializable
    data class BochaResponse(
        @SerialName("code")
        val code: Int,
        @SerialName("log_id")
        val logId: String? = null,
        @SerialName("msg")
        val msg: String? = null,
        @SerialName("data")
        val data: BochaData? = null
    )

    @Serializable
    data class BochaData(
        @SerialName("_type")
        val type: String? = null,
        @SerialName("queryContext")
        val queryContext: BochaQueryContext? = null,
        @SerialName("webPages")
        val webPages: BochaWebPages? = null,
    )

    @Serializable
    data class BochaQueryContext(
        @SerialName("originalQuery")
        val originalQuery: String
    )

    @Serializable
    data class BochaWebPages(
        @SerialName("webSearchUrl")
        val webSearchUrl: String? = null,
        @SerialName("totalEstimatedMatches")
        val totalEstimatedMatches: Long? = null,
        @SerialName("value")
        val value: List<BochaWebPage> = emptyList(),
        @SerialName("someResultsRemoved")
        val someResultsRemoved: Boolean? = null
    )

    @Serializable
    data class BochaWebPage(
        @SerialName("id")
        val id: String? = null,
        @SerialName("name")
        val name: String,
        @SerialName("url")
        val url: String,
        @SerialName("displayUrl")
        val displayUrl: String? = null,
        @SerialName("snippet")
        val snippet: String,
        @SerialName("summary")
        val summary: String? = null,
        @SerialName("siteName")
        val siteName: String? = null,
        @SerialName("siteIcon")
        val siteIcon: String? = null,
        @SerialName("dateLastCrawled")
        val dateLastCrawled: String? = null,
        @SerialName("cachedPageUrl")
        val cachedPageUrl: String? = null,
        @SerialName("language")
        val language: String? = null,
        @SerialName("isFamilyFriendly")
        val isFamilyFriendly: Boolean? = null,
        @SerialName("isNavigational")
        val isNavigational: Boolean? = null
    )
}

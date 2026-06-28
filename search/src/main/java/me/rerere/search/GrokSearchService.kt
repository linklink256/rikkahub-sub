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
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.json
import okhttp3.Response

private const val TAG = "GrokSearchService"

object GrokSearchService : HttpSearchService<SearchServiceOptions.GrokOptions>() {
    override val name: String = "Grok"

    @Composable
    override fun Description() = ApiKeyButton("https://console.x.ai/")

    override fun parameters(options: SearchServiceOptions.GrokOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "The question to ask, can be a natural language question")
                })
            },
            required = listOf("query")
        )

    override fun buildUrl(
        query: String,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.GrokOptions
    ): String = serviceOptions.customUrl

    override fun buildRequestBody(
        query: String,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.GrokOptions
    ): String? = buildJsonObject {
        put("model", JsonPrimitive(serviceOptions.model))
        put("input", buildJsonArray {
            add(buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", JsonPrimitive(serviceOptions.systemPrompt))
            })
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonPrimitive(query))
            })
        })
        put("tools", buildJsonArray {
            add(buildJsonObject {
                put("type", JsonPrimitive("web_search"))
            })
            add(buildJsonObject {
                put("type", JsonPrimitive("x_search"))
            })
        })
        put("store", JsonPrimitive(false))
    }.toString()

    override fun validateResponse(response: Response) {
        if (!response.isSuccessful) {
            error("response failed #${response.code}: ${response.body?.string()}")
        }
    }

    override fun parseSearchResponse(raw: String): SearchResult {
        val responseBody = json.decodeFromString<GrokResponse>(raw)

        val messageOutput = responseBody.output.firstOrNull {
            it.type == "message" && it.role == "assistant"
        }
        val textContent = messageOutput?.content?.firstOrNull {
            it.type == "output_text"
        }

        val answer = textContent?.text

        val items = textContent?.annotations
            ?.filter { it.type == "url_citation" && !it.url.isNullOrBlank() }
            ?.distinctBy { it.url }
            ?.map { annotation ->
                SearchResultItem(
                    title = annotation.url!!,
                    url = annotation.url,
                    text = ""
                )
            } ?: emptyList()

        return SearchResult(
            answer = answer,
            items = items
        )
    }

    override fun extractApiKey(serviceOptions: SearchServiceOptions.GrokOptions): String {
        if (serviceOptions.apiKey.isBlank()) {
            error("Grok API key is required")
        }
        return serviceOptions.apiKey
    }

    @Serializable
    private data class GrokResponse(
        val output: List<GrokOutputItem> = emptyList()
    )

    @Serializable
    private data class GrokOutputItem(
        val type: String,
        val role: String? = null,
        val status: String? = null,
        val content: List<GrokContent>? = null,
    )

    @Serializable
    private data class GrokContent(
        val type: String,
        val text: String? = null,
        val annotations: List<GrokAnnotation>? = null
    )

    @Serializable
    private data class GrokAnnotation(
        val type: String,
        val url: String? = null,
        val title: String? = null,
        @SerialName("start_index") val startIndex: Int? = null,
        @SerialName("end_index") val endIndex: Int? = null
    )
}

package me.rerere.search

import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.util.KeyRoulette
import me.rerere.common.http.await
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import android.util.Log
import kotlin.reflect.KClass
import kotlin.uuid.Uuid

// ponytail: shared query-property builder — dedup'd from 15+ identical blocks
fun JsonObjectBuilder.queryField(description: String = "search keyword") {
    put("query", buildJsonObject {
        put("type", "string")
        put("description", description)
    })
}

// ponytail: decode with debug-on-failure — dedup'd from 4 identical blocks
inline fun <reified T> Json.decodeOrThrow(raw: String): T = runCatching {
    decodeFromString<T>(raw)
}.onFailure {
    it.printStackTrace()
    println(raw)
    error("Failed to decode response: $raw")
}.getOrThrow()

// ponytail: query extraction — dedup'd from 17 identical blocks
fun JsonObject.requireQuery(): String =
    this["query"]?.jsonPrimitive?.content ?: error("query is required")

// ponytail: response success guard — dedup'd from 8 identical blocks
fun okhttp3.Response.requireSuccess() {
    if (!isSuccessful) error("request failed #${code}")
}

interface SearchService<T : SearchServiceOptions> {
    val name: String

    fun parameters(options: T): InputSchema? = InputSchema.Obj(
        properties = buildJsonObject { queryField() },
        required = listOf("query")
    )

    fun scrapingParameters(options: T): InputSchema? = null

    @Composable
    fun Description()

    suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): Result<SearchResult>

    suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): Result<ScrapedResult> = Result.failure(UnsupportedOperationException("Scrape not supported"))

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T : SearchServiceOptions> getService(options: T): SearchService<T> {
            return when (options) {
                is SearchServiceOptions.TavilyOptions -> TavilySearchService
                is SearchServiceOptions.ExaOptions -> ExaSearchService
                is SearchServiceOptions.ZhipuOptions -> ZhipuSearchService
                is SearchServiceOptions.BingLocalOptions -> BingSearchService
                is SearchServiceOptions.SearXNGOptions -> SearXNGService
                is SearchServiceOptions.LinkUpOptions -> LinkUpService
                is SearchServiceOptions.BraveOptions -> BraveSearchService
                is SearchServiceOptions.MetasoOptions -> MetasoSearchService
                is SearchServiceOptions.OllamaOptions -> OllamaSearchService
                is SearchServiceOptions.PerplexityOptions -> PerplexitySearchService
                is SearchServiceOptions.FirecrawlOptions -> FirecrawlSearchService
                is SearchServiceOptions.JinaOptions -> JinaSearchService
                is SearchServiceOptions.BochaOptions -> BochaSearchService
                is SearchServiceOptions.RikkaHubOptions -> RikkaHubSearchService
                is SearchServiceOptions.GrokOptions -> GrokSearchService
                is SearchServiceOptions.TinyfishOptions -> TinyfishSearchService
                is SearchServiceOptions.SerperOptions -> SerperSearchService
                is SearchServiceOptions.CustomJsOptions -> CustomJsSearchService
            } as SearchService<T>
        }

        @Volatile
        internal var httpClient: OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        @Volatile
        internal var keyRoulette: KeyRoulette = KeyRoulette.default()

        fun init(client: OkHttpClient, context: Context? = null) {
            httpClient = client
            keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
        }

        internal val json by lazy {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
    }
}

@Serializable
data class SearchCommonOptions(
    val resultSize: Int = 10
)

@Serializable
data class SearchResult(
    val answer: String? = null,
    val items: List<SearchResultItem>,
    val images: List<String> = emptyList(),
) {
    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val text: String,
    )
}

@Serializable
data class ScrapedResult(
    val urls: List<ScrapedResultUrl>,
)

@Serializable
data class ScrapedResultUrl(
    val url: String,
    val content: String,
    val metadata: ScrapedResultMetadata? = null,
)

@Serializable
data class ScrapedResultMetadata(
    val title: String? = null,
    val description: String? = null,
    val language: String? = null,
)

@Serializable
sealed class SearchServiceOptions {
    abstract val id: Uuid

    open val displayName: String
        get() = TYPES[this::class] ?: "Unknown"

    companion object {
        private const val TAG = "SearchServiceOptions"

        val DEFAULT = BingLocalOptions()

        val TYPES = mapOf(
            BingLocalOptions::class to "Bing",
            RikkaHubOptions::class to "RikkaHub",
            ZhipuOptions::class to "智谱",
            TavilyOptions::class to "Tavily",
            ExaOptions::class to "Exa",
            SearXNGOptions::class to "SearXNG",
            LinkUpOptions::class to "LinkUp",
            BraveOptions::class to "Brave",
            MetasoOptions::class to "秘塔",
            OllamaOptions::class to "Ollama",
            PerplexityOptions::class to "Perplexity",
            FirecrawlOptions::class to "Firecrawl",
            JinaOptions::class to "Jina",
            BochaOptions::class to "博查",
            GrokOptions::class to "Grok",
            TinyfishOptions::class to "Tinyfish",
            SerperOptions::class to "Serper",
            CustomJsOptions::class to "Custom JS",
        )

        // 解码磁盘上的 search_services 原始字符串。
        // - null：全新用户（磁盘无该键）→ 保留产品默认，给一个 BingLocalOptions。
        // - 解码失败（数据损坏 / 历史不兼容，如缺 type 判别符、未知供应商、非法 JSON）
        //   → 诚实清空为空列表，而非伪装成单个 Bing，避免误导用户以为自己选的就是 Bing。
        //   历史不兼容数据一旦被用户重配并保存，即以当前格式写回，自愈。
        // - 正常成功 → 保留各子类运行时类型与顺序。
        fun decodeListSafely(raw: String?): List<SearchServiceOptions> =
            if (raw == null) listOf(DEFAULT)
            else try {
                decodeJson.decodeFromString<List<SearchServiceOptions>>(raw)
            } catch (e: Exception) {
                // Json.decodeFromString 只处理内存字符串，不会抛 IOException；
                // 若将来在此混入 I/O，须重抛 IOException 以交由上层 DataStore 处理。
                if (e is java.io.IOException) throw e
                Log.w(TAG, "decodeListSafely: search_services decode failed, clearing list (not faking Bing)", e)
                emptyList()
            }

        private val decodeJson = Json { ignoreUnknownKeys = true }

        // 显式工厂：按选中类型直接构造对应实例，替代反射 primaryConstructor!!.callBy()。
        // 反射在 release R8/minify 下易被裁剪/失效且无 keep 保护，显式 when 更稳、可读、不依赖反射。
        // 新增供应商子类时须同步在此加分支（else 会运行时报错而非静默造错实例）。
        fun create(type: KClass<out SearchServiceOptions>): SearchServiceOptions = when (type) {
            BingLocalOptions::class -> BingLocalOptions()
            RikkaHubOptions::class -> RikkaHubOptions()
            ZhipuOptions::class -> ZhipuOptions()
            TavilyOptions::class -> TavilyOptions()
            ExaOptions::class -> ExaOptions()
            SearXNGOptions::class -> SearXNGOptions()
            LinkUpOptions::class -> LinkUpOptions()
            BraveOptions::class -> BraveOptions()
            MetasoOptions::class -> MetasoOptions()
            OllamaOptions::class -> OllamaOptions()
            PerplexityOptions::class -> PerplexityOptions()
            FirecrawlOptions::class -> FirecrawlOptions()
            JinaOptions::class -> JinaOptions()
            BochaOptions::class -> BochaOptions()
            GrokOptions::class -> GrokOptions()
            TinyfishOptions::class -> TinyfishOptions()
            SerperOptions::class -> SerperOptions()
            CustomJsOptions::class -> CustomJsOptions()
            else -> error("Unknown SearchServiceOptions type: $type")
        }
    }

    @Serializable
    @SerialName("bing_local")
    class BingLocalOptions(
        override val id: Uuid = Uuid.random()
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("zhipu")
    data class ZhipuOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("tavily")
    data class TavilyOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val depth: String = "advanced",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("exa")
    data class ExaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("searxng")
    data class SearXNGOptions(
        override val id: Uuid = Uuid.random(),
        val url: String = "",
        val engines: String = "",
        val language: String = "",
        val username: String = "",
        val password: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("linkup")
    data class LinkUpOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val depth: String = "standard",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("brave")
    data class BraveOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("metaso")
    data class MetasoOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("ollama")
    data class OllamaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("perplexity")
    data class PerplexityOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val maxTokens: Int? = null,
        val maxTokensPerPage: Int? = null,
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("firecrawl")
    data class FirecrawlOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("jina")
    data class JinaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val searchUrl: String = "https://s.jina.ai/",
        val scrapeUrl: String = "https://r.jina.ai/",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("bocha")
    data class BochaOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val summary: Boolean = true,
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("rikkahub")
    data class RikkaHubOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val depth: String = "standard",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("grok")
    data class GrokOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
        val model: String = "grok-4-1-fast-non-reasoning",
        val customUrl: String = "https://api.x.ai/v1/responses",
        val systemPrompt: String = "You are a helpful search assistant. Search the web to find accurate and up-to-date information for the user's query. Provide a comprehensive answer with citations.",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("tinyfish")
    data class TinyfishOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("serper")
    data class SerperOptions(
        override val id: Uuid = Uuid.random(),
        val apiKey: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("custom_js")
    data class CustomJsOptions(
        override val id: Uuid = Uuid.random(),
        val name: String = "",
        val searchScript: String = DEFAULT_SEARCH_SCRIPT,
        val scrapeScript: String = "",
    ) : SearchServiceOptions() {
        override val displayName: String
            get() = name.ifBlank { "Custom JS" }
        companion object {
            const val DEFAULT_SCRAPE_SCRIPT = """// Implement scrape(urls) function
// urls is an array of URL strings
// Use fetch(url, options?) for HTTP requests
// fetch() returns { status, ok, text(), json() }
// Return { urls: [{ url, content, metadata?: { title?, description?, language? } }] }

function scrape(urls) {
  return {
    urls: urls.map(function(url) {
      const res = fetch(url);
      const body = res.text();
      return { url: url, content: body };
    })
  };
}"""

            const val DEFAULT_SEARCH_SCRIPT = """// Implement search(query, resultSize) function
// Use fetch(url, options?) for HTTP requests
// fetch() returns { status, ok, text(), json() }
// Return { items: [{ title, url, text }], answer?: string }

function search(query, resultSize) {
  const encoded = encodeURIComponent(query);
  const res = fetch("https://example.com/search?q=" + encoded + "&limit=" + resultSize);
  const data = res.json();
  return {
    items: data.results.map(function(r) {
      return { title: r.title, url: r.url, text: r.snippet };
    })
  };
}"""
        }
    }
}

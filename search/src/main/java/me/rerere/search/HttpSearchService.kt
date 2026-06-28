package me.rerere.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import me.rerere.common.http.await
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Thin abstract base for HTTP-based search services.
 *
 * Provides a final template method [search] that handles the common
 * HTTP request/response lifecycle, with open extension points for
 * subclasses to customize each step.
 */
abstract class HttpSearchService<T : SearchServiceOptions> : SearchService<T> {

    // ── Extension points ──────────────────────────────────────────

    /** Base URL for the API endpoint. Defaults to empty string. */
    open val baseUrl: String = ""

    /** HTTP method to use. Defaults to "POST". */
    open val httpMethod: String = "POST"

    /** Name of the authentication header. Defaults to "Authorization". */
    open val authHeaderName: String = "Authorization"

    /** Prefix prepended to the API key value. Defaults to "Bearer ". */
    open val authHeaderPrefix: String = "Bearer "

    /** Whether to use key roulette for API key rotation. */
    open val useKeyRoulette: Boolean = false

    /**
     * Build the request URL.
     * Default implementation returns [baseUrl].
     */
    open fun buildUrl(
        query: String,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): String = baseUrl

    /**
     * Build the request body as a JSON string.
     * Return `null` for GET requests that have no body.
     */
    open fun buildRequestBody(
        query: String,
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): String? = null

    /**
     * Additional headers to include in every request.
     * Default returns an empty map.
     */
    open fun extraHeaders(serviceOptions: T): Map<String, String> = emptyMap()

    /**
     * Validate the HTTP response.
     * Default implementation calls [Response.requireSuccess].
     * Override to customize error messages (can safely read
     * response.body here — the template reads the body after
     * this check passes).
     */
    open fun validateResponse(response: Response) {
        response.requireSuccess()
    }

    /**
     * Parse the raw response body string into a [SearchResult].
     * Must be implemented by every concrete subclass.
     */
    abstract fun parseSearchResponse(raw: String, commonOptions: SearchCommonOptions): SearchResult

    /**
     * Extract the API key from service options.
     * Subclasses with an apiKey field override this.
     */
    protected abstract fun extractApiKey(serviceOptions: T): String

    // ── Template method ───────────────────────────────────────────

    final override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params.requireQuery()

            // Resolve API key (key roulette or plain)
            val apiKey = if (useKeyRoulette) {
                keyRoulette.next(extractApiKey(serviceOptions), serviceOptions.id.toString())
            } else {
                extractApiKey(serviceOptions)
            }

            // Build URL
            val url = buildUrl(query, params, commonOptions, serviceOptions)

            // Build request
            val requestBuilder = Request.Builder().url(url)

            // Authentication header
            if (apiKey.isNotEmpty()) {
                requestBuilder.addHeader(authHeaderName, authHeaderPrefix + apiKey)
            }

            // Extra headers
            extraHeaders(serviceOptions).forEach { (name, value) ->
                requestBuilder.addHeader(name, value)
            }

            // HTTP method and body
            val bodyJson = buildRequestBody(query, params, commonOptions, serviceOptions)
            if (httpMethod == "POST") {
                val body = (bodyJson ?: "{}").toRequestBody("application/json".toMediaType())
                requestBuilder.post(body)
            } else {
                requestBuilder.get()
            }

            // Execute
            val response = httpClient.newCall(requestBuilder.build()).await()

            // Validate
            validateResponse(response)

            // Parse
            val bodyString = response.body?.string() ?: error("Empty response body")
            parseSearchResponse(bodyString, commonOptions)
        }
    }
}

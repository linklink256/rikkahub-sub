package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolAnnotations
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.ProxyConfig
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * Network proxy tool: set/query the app's global HTTP proxy.
 * Uses JVM ProxySelector.setDefault() so it affects ALL outbound connections
 * (OkHttp clients AND HttpURLConnection used by the fetch tool) immediately,
 * without needing to rebuild any client.
 */
internal fun networkProxyTool(settingsStore: SettingsStore): Tool = Tool(
    name = "network_proxy",
    annotations = ToolAnnotations(destructiveHint = true, openWorldHint = true),
    description = """
        Set, clear, or query the app's global HTTP proxy. Affects all outbound
        network requests (AI providers, web search, fetch). Use action:
        "set" (provide host/port, optional username/password),
        "clear" (disable proxy),
        or "query" (read current config, default).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Action: \"set\", \"clear\", or \"query\" (default)")
                })
                put("host", buildJsonObject {
                    put("type", "string")
                    put("description", "Proxy host (for \"set\")")
                })
                put("port", buildJsonObject {
                    put("type", "integer")
                    put("description", "Proxy port (for \"set\")")
                })
                put("username", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional proxy username (for \"set\")")
                })
                put("password", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional proxy password (for \"set\")")
                })
            }
        )
    },
    execute = {
        val params = it.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: "query"
        when (action) {
            "set" -> {
                val host = params["host"]?.jsonPrimitive?.contentOrNull
                val port = params["port"]?.jsonPrimitive?.intOrNull
                if (host.isNullOrBlank() || port == null || port !in 1..65535) {
                    error("host and a valid port (1-65535) are required for \"set\"")
                }
                val username = params["username"]?.jsonPrimitive?.contentOrNull ?: ""
                val password = params["password"]?.jsonPrimitive?.contentOrNull ?: ""
                val config = ProxyConfig(enabled = true, host = host, port = port, username = username, password = password)
                settingsStore.update { it.copy(proxyConfig = config) }
                applyGlobalProxy(config)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("proxy", "${host}:${port}")
                    if (username.isNotBlank()) put("authenticated", true)
                    put("message", "Global proxy set and applied immediately")
                }.toString()))
            }
            "clear" -> {
                settingsStore.update { it.copy(proxyConfig = ProxyConfig()) }
                applyGlobalProxy(ProxyConfig())
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("message", "Proxy cleared")
                }.toString()))
            }
            "query" -> {
                val config = settingsStore.settingsFlow.value.proxyConfig
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("enabled", config.enabled)
                    put("host", config.host)
                    put("port", config.port)
                    if (config.username.isNotBlank()) put("has_auth", true)
                    put("is_configured", config.isConfigured)
                }.toString()))
            }
            else -> error("unknown action: $action, must be one of [set, clear, query]")
        }
    }
)

/**
 * Apply (or clear) the global JVM proxy via [ProxySelector].
 *
 * OkHttp respects the default ProxySelector; HttpURLConnection also respects it.
 * Setting it at JVM level means a config change takes effect immediately for all
 * outbound connections without rebuilding any OkHttpClient.
 *
 * Auth (username/password): HTTP proxy auth is handled via an Authenticator on
 * the DI OkHttpClient (see DataSourceModule) — for HttpURLConnection-based calls
 * the proxy auth is best-effort. If auth is configured, we also set
 * java.net.Authenticator.setDefault so HttpURLConnection can get credentials.
 */
fun applyGlobalProxy(config: ProxyConfig) {
    if (config.isConfigured) {
        val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(config.host, config.port))
        java.net.ProxySelector.setDefault(object : java.net.ProxySelector() {
            override fun select(uri: java.net.URI?): MutableList<java.net.Proxy> = mutableListOf(proxy)
            override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {}
        })
        if (config.username.isNotBlank()) {
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): java.net.Authenticator.PasswordAuthentication {
                    return java.net.Authenticator.PasswordAuthentication(config.username, config.password.toCharArray())
                }
            })
        }
    } else {
        // Restore default (no proxy / system default)
        java.net.ProxySelector.setDefault(null)
        java.net.Authenticator.setDefault(null)
    }
}

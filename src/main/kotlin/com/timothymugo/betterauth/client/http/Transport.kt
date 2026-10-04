package com.timothymugo.betterauth.client.http

import com.timothymugo.betterauth.client.config.BetterAuthConfig
import com.timothymugo.betterauth.client.plugin.PluginHooks
import com.timothymugo.betterauth.client.plugin.RequestContext
import com.timothymugo.betterauth.client.plugin.ResponseContext
import com.timothymugo.betterauth.client.result.BetterAuthError
import com.timothymugo.betterauth.client.result.BetterAuthResult
import com.timothymugo.betterauth.client.session.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The JSON configuration shared by every request and response. */
internal val BetterAuthJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = true
    coerceInputValues = true
}

/** A completed HTTP exchange (status < 400). Redirects are *not* followed, so 3xx lands here with [location]. */
internal class RawResponse(
    val status: Int,
    val headers: ResponseHeaders,
    val bodyText: String,
) {
    val location: String? get() = headers.get("location")
    val isRedirect: Boolean get() = status in 300..399
}

/**
 * Owns the Ktor client and the session lifecycle. One [Transport] backs a whole `BetterAuthClient`;
 * [scoped] derives a view with an independent session that shares the connection pool.
 */
internal class Transport private constructor(
    private val config: BetterAuthConfig,
    private val http: HttpClient,
    private val ownsClient: Boolean,
    val store: SessionStore,
    private val sessionTransport: SessionTransport,
    private val clock: () -> Long,
    private val sessionMutex: Mutex,
    private val lockStripes: Array<Mutex>,
    private val hooks: List<PluginHooks>,
) {
    private val baseUrl = config.baseUrl.trimEnd('/')

    /** `Origin` header value: the configured one, else the origin of the base URL (Better Auth trusts its own). */
    private val origin: String? = when {
        config.origin == null -> originOf(baseUrl)
        config.origin.isNullOrBlank() -> null
        else -> config.origin
    }
    val cookiePrefix: String get() = config.cookiePrefix
    val storagePrefix: String get() = config.storagePrefix
    val cacheEnabled: Boolean get() = !config.disableCache

    /** Issues a request. Returns [BetterAuthResult.Failure] for network errors and for status >= 400. */
    suspend fun execute(
        method: HttpMethod,
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: JsonElement? = null,
    ): BetterAuthResult<RawResponse> {
        val state = store.get()
        val outgoing = sessionTransport.outgoingHeaders(state, config.cookiePrefix, clock())

        // Plugin hooks may add headers and rewrite the query and the body (in registration order).
        val request = RequestContext(method.value, path, LinkedHashMap(), LinkedHashMap(query), body)
        for (hook in hooks) hook.onRequest?.invoke(request)

        val response = try {
            http.request {
                this.method = method
                url(buildUrl(path, request.query))
                header("Accept", "application/json")
                header("User-Agent", config.userAgent)
                origin?.let { header("Origin", it) }
                config.expoOrigin?.let { header("expo-origin", it) }
                config.headers.forEach { (k, v) -> header(k, v) }
                // The session's own Authorization/Cookie always win over a plugin that set the same header.
                request.headers.filterKeys { k -> outgoing.keys.none { it.equals(k, ignoreCase = true) } }
                    .forEach { (k, v) -> header(k, v) }
                outgoing.forEach { (k, v) -> header(k, v) }
                applyBody(method, request.body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return BetterAuthResult.Failure(BetterAuthError.Network(e))
        }

        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return BetterAuthResult.Failure(BetterAuthError.Network(e))
        }

        val headers = ResponseHeaders(response.headers.entries().associate { it.key to it.value })
        sessionMutex.withLock {
            val current = store.get()
            val updated = sessionTransport.applyResponse(current, headers, config.cookiePrefix, clock())
            if (updated != current) store.set(updated)
        }

        val status = response.status.value
        if (hooks.any { it.onResponse != null }) {
            val context = ResponseContext(method.value, path, status, headers, text)
            for (hook in hooks) hook.onResponse?.invoke(context)
        }
        return if (status < 400) {
            BetterAuthResult.Success(RawResponse(status, headers, text))
        } else {
            BetterAuthResult.Failure(parseApiError(status, text, response.status.description))
        }
    }

    /** Issues a request and returns the JSON body as-is. */
    suspend fun executeJson(
        method: HttpMethod,
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: JsonElement? = null,
    ): BetterAuthResult<JsonElement> = when (val raw = execute(method, path, query, body)) {
        is BetterAuthResult.Failure -> raw
        is BetterAuthResult.Success -> decode(raw.value, JsonElement.serializer())
    }

    suspend fun <T> executeDecoded(
        method: HttpMethod,
        path: String,
        deserializer: DeserializationStrategy<T>,
        query: Map<String, String?> = emptyMap(),
        body: JsonElement? = null,
    ): BetterAuthResult<T> = call(method, path, deserializer, query, body)

    /** Issues a request and decodes the JSON body with [deserializer]. */
    suspend fun <T> call(
        method: HttpMethod,
        path: String,
        deserializer: DeserializationStrategy<T>,
        query: Map<String, String?> = emptyMap(),
        body: JsonElement? = null,
    ): BetterAuthResult<T> = when (val raw = execute(method, path, query, body)) {
        is BetterAuthResult.Failure -> raw
        is BetterAuthResult.Success -> decode(raw.value, deserializer)
    }

    fun <T> decode(raw: RawResponse, deserializer: DeserializationStrategy<T>): BetterAuthResult<T> = try {
        val element = if (raw.bodyText.isBlank()) JsonNull else BetterAuthJson.parseToJsonElement(raw.bodyText)
        BetterAuthResult.Success(BetterAuthJson.decodeFromJsonElement(deserializer, element))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        BetterAuthResult.Failure(BetterAuthError.Decoding(e, raw.bodyText))
    }

    /**
     * Forgets the session credentials (token, JWT, session and account cookies) but keeps unrelated cookies such as
     * the two-factor `trust_device` marker.
     */
    suspend fun clearSessionCredentials() {
        sessionMutex.withLock {
            store.set(CookieJar.clearSession(store.get(), config.cookiePrefix))
            store.setSessionCache(null)
        }
    }

    /** Feeds `Set-Cookie` lines (for example from a deep link) through the jar as if a response had carried them. */
    suspend fun importSetCookies(lines: List<String>) {
        sessionMutex.withLock {
            val headers = ResponseHeaders(mapOf("set-cookie" to lines))
            store.set(sessionTransport.applyResponse(store.get(), headers, config.cookiePrefix, clock()))
        }
    }

    suspend fun oauthState(): String? = CookieJar.oauthState(store.get(), config.cookiePrefix)

    fun authorizationProxyUrl(authorizationUrl: String, oauthState: String?): String =
        buildUrl(
            "/expo-authorization-proxy",
            mapOf("authorizationURL" to authorizationUrl, "oauthState" to oauthState),
        )

    /** A client view with its own session store that reuses this transport's connection pool. */
    fun scoped(store: SessionStore, lockKey: Any? = null): Transport = Transport(
        config, http, ownsClient = false, store, sessionTransport, clock,
        // Views over the same storage and prefix must take turns on their read-modify-write, even when created per
        // request. Striped, so locks are shared without being retained per user.
        sessionMutex = lockKey?.let { lockStripes[(it.hashCode() and Int.MAX_VALUE) % lockStripes.size] } ?: Mutex(),
        lockStripes = lockStripes,
        hooks = hooks,
    )

    fun close() {
        if (ownsClient) http.close()
    }

    private fun buildUrl(path: String, query: Map<String, String?>): String {
        val sb = StringBuilder(baseUrl)
        if (!path.startsWith("/")) sb.append('/')
        sb.append(path)
        val params = query.filterValues { it != null }
        if (params.isNotEmpty()) {
            sb.append('?')
            sb.append(params.entries.joinToString("&") { "${it.key.encodeURLParameter()}=${it.value!!.encodeURLParameter()}" })
        }
        return sb.toString()
    }

    private fun HttpRequestBuilder.applyBody(method: HttpMethod, body: JsonElement?) {
        if (method == HttpMethod.Get || method == HttpMethod.Head) return
        // Better Auth rejects an empty body on JSON POSTs, so always send at least `{}`.
        val payload = body ?: JsonObject(emptyMap())
        setBody(TextContent(BetterAuthJson.encodeToString(JsonElement.serializer(), payload), ContentType.Application.Json))
    }

    private fun parseApiError(status: Int, text: String, statusText: String): BetterAuthError.Api {
        val element = runCatching { BetterAuthJson.parseToJsonElement(text) }.getOrNull()
        val obj = element as? JsonObject
        val code = (obj?.get("code") as? JsonPrimitive)?.contentOrNull
        val message = (obj?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: text.takeIf { it.isNotBlank() && obj == null }?.take(500)
            ?: statusText.ifBlank { "HTTP $status" }
        return BetterAuthError.Api(status, code, message, element)
    }

    companion object {
        private const val LOCK_STRIPES = 64

        fun create(
            config: BetterAuthConfig,
            hooks: List<PluginHooks> = emptyList(),
            clock: () -> Long = { System.currentTimeMillis() },
        ): Transport {
            config.validate()
            val engine: HttpClientEngine = config.engine ?: OkHttp.create()
            val client = HttpClient(engine) {
                followRedirects = false
                expectSuccess = false
                install(HttpTimeout) {
                    requestTimeoutMillis = config.requestTimeoutMillis
                    connectTimeoutMillis = config.connectTimeoutMillis
                }
            }
            return Transport(
                config, client, ownsClient = true, config.resolveSessionStore(), config.resolveTransport(), clock,
                sessionMutex = Mutex(), lockStripes = Array(LOCK_STRIPES) { Mutex() }, hooks = hooks,
            )
        }
    }
}

/** `scheme://host[:port]` of [url], what a browser would send as `Origin`. */
internal fun originOf(url: String): String {
    val parsed = Url(url)
    val port = if (parsed.specifiedPort > 0) ":${parsed.specifiedPort}" else ""
    return "${parsed.protocol.name}://${parsed.host}$port"
}

package com.timothymugo.betterauth.client.plugin

import com.timothymugo.betterauth.client.config.BetterAuthConfig
import com.timothymugo.betterauth.client.http.BetterAuthJson
import com.timothymugo.betterauth.client.http.ResponseHeaders
import com.timothymugo.betterauth.client.http.Transport
import com.timothymugo.betterauth.client.result.BetterAuthResult
import com.timothymugo.betterauth.client.session.StoredSession
import io.ktor.http.HttpMethod
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/**
 * Identifies one plugin's API on a client. Create one per plugin and keep it in a `val`:
 * the same instance must be used to register the plugin and to look its API up.
 *
 * @property name The plugin's name, used in error messages.
 * @property installHint What the user has to add to `plugins(...)`, e.g. `twoFactorClient()`.
 */
public class PluginKey<A : Any>(public val name: String, public val installHint: String) {
    override fun toString(): String = name
}

/**
 * A Better Auth client plugin, the counterpart of the plugins passed to `createAuthClient({ plugins: [...] })` in the
 * TypeScript client. A client only has the plugins you register, and a plugin's API is only created when first used:
 *
 * ```kotlin
 * val auth = BetterAuthClient {
 *     baseUrl = "https://api.example.com/api/auth"
 *     plugins(twoFactorClient(), organizationClient())
 * }
 * auth.twoFactor.enable(...)      // registered
 * auth.admin.listUsers()          // throws: Plugin 'admin' is not installed. Add adminClient() to plugins(...)
 * ```
 *
 * A plugin can contribute an API ([createApi]), request and response [hooks], and adjust the client's
 * configuration ([configure]); the third is how a platform plugin sets its defaults.
 */
public interface ClientPlugin<A : Any> {
    public val key: PluginKey<A>

    /** Called once, in registration order, while the client is built; may set defaults on [config]. */
    public fun configure(config: BetterAuthConfig) {}

    /** Hooks run on every request of every view of the client (including `withSession`/`withStorage` views). */
    public val hooks: PluginHooks get() = PluginHooks()

    /**
     * Creates this plugin's API. Called lazily, at most once per client view: `withSession` and `withStorage` views get
     * their own instance, bound to their own session.
     */
    public fun createApi(context: PluginContext): A
}

/**
 * Request and response hooks, the counterpart of Better Auth's fetch plugins. Hooks run in registration order.
 * An exception thrown by a hook is a bug in the plugin and propagates to the caller of the request.
 */
public class PluginHooks(
    /** Runs before a request is sent. May add headers, change query parameters and rewrite the body. */
    public val onRequest: (suspend (RequestContext) -> Unit)? = null,
    /** Runs after a response arrived (any status), after the SDK has stored its cookies and token. */
    public val onResponse: (suspend (ResponseContext) -> Unit)? = null,
)

/** A request about to be sent. Changes made by a hook apply to the request. */
public class RequestContext internal constructor(
    /** `GET` or `POST`. */
    public val method: String,
    /** Path relative to `baseUrl`, e.g. `/sign-in/email`. */
    public val path: String,
    /** Extra headers to send. The session's own `Authorization` and `Cookie` headers are added after these. */
    public val headers: MutableMap<String, String>,
    public val query: MutableMap<String, String?>,
    /** The JSON body (`null` for GET). Replace it to rewrite the request. */
    public var body: JsonElement?,
)

/** A response that was received. */
public class ResponseContext internal constructor(
    public val method: String,
    public val path: String,
    public val status: Int,
    public val headers: ResponseHeaders,
    private val bodyText: String,
) {
    /** The body parsed as JSON, or `null` if it is empty or not JSON. */
    public val body: JsonElement? by lazy {
        runCatching { BetterAuthJson.parseToJsonElement(bodyText) }.getOrNull()
    }
}

/** What a plugin gets when its API is created: access to the client it belongs to. */
public class PluginContext internal constructor(internal val transport: Transport) {
    /**
     * Calls any Better Auth endpoint through this client view's session, hooks and error handling. This is how a
     * plugin written outside this library talks to the server.
     * [method] is `"GET"` or `"POST"`; for GET put parameters in [query], for POST in [body].
     */
    public suspend fun request(
        method: String,
        path: String,
        body: JsonElement? = null,
        query: Map<String, String?> = emptyMap(),
    ): BetterAuthResult<JsonElement> = transport.executeJson(HttpMethod.parse(method.uppercase()), path, query, body)

    /** Like [request], decoding the response with [deserializer]. */
    public suspend fun <T> request(
        method: String,
        path: String,
        deserializer: DeserializationStrategy<T>,
        body: JsonElement? = null,
        query: Map<String, String?> = emptyMap(),
    ): BetterAuthResult<T> = transport.executeDecoded(HttpMethod.parse(method.uppercase()), path, deserializer, query, body)

    /** The session this view currently holds (token, JWT, cookies). */
    public suspend fun storedSession(): StoredSession = transport.store.get()
}

/** Like [PluginContext.request], decoding into [T]. */
public suspend inline fun <reified T> PluginContext.request(
    method: String,
    path: String,
    body: JsonElement? = null,
    query: Map<String, String?> = emptyMap(),
): BetterAuthResult<T> = request(method, path, serializer<T>(), body, query)

/** The plugins a client was built with, in registration order. Shared by every view of the client. */
internal class PluginRegistry(plugins: List<ClientPlugin<*>>) {
    private val byKey: Map<PluginKey<*>, ClientPlugin<*>> = LinkedHashMap<PluginKey<*>, ClientPlugin<*>>().also { map ->
        for (plugin in plugins) {
            require(map.put(plugin.key, plugin) == null) {
                "Plugin '${plugin.key.name}' is registered twice. Register ${plugin.key.installHint} once."
            }
        }
    }

    val hooks: List<PluginHooks> = byKey.values.map { it.hooks }

    operator fun contains(key: PluginKey<*>): Boolean = key in byKey

    @Suppress("UNCHECKED_CAST")
    fun <A : Any> find(key: PluginKey<A>): ClientPlugin<A>? = byKey[key] as ClientPlugin<A>?

    companion object {
        val Empty = PluginRegistry(emptyList())
    }
}

package com.timothymugo.betterauth.client.android

import android.content.Context
import com.timothymugo.betterauth.client.BetterAuthClient
import com.timothymugo.betterauth.client.config.BetterAuthConfig
import com.timothymugo.betterauth.client.plugin.ClientPlugin
import com.timothymugo.betterauth.client.plugin.PluginContext
import com.timothymugo.betterauth.client.plugin.PluginHooks
import com.timothymugo.betterauth.client.plugin.PluginKey
import com.timothymugo.betterauth.client.storage.KeyValueStorage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Options of [androidClient]. Like the options of the Better Auth Expo client plugin. */
public class AndroidClientConfig {
    /**
     * Your app's deep-link scheme, e.g. `"myapp"` for `myapp://`. Sent as the `expo-origin` header and used to turn
     * relative callback URLs (`"/dashboard"`) into deep links (`"myapp://dashboard"`). Add `myapp://` to the server's
     * `trustedOrigins`. Without it the plugin only provides storage.
     */
    public var scheme: String? = null

    /**
     * Where the session is persisted. Defaults to [EncryptedDataStoreStorage] (Jetpack DataStore encrypted with an Android
     * Keystore key). Ignored when the client already has a `storage` or `sessionStore` of its own.
     */
    public var storage: KeyValueStorage? = null

    /** File name of the default storage, `<files dir>/datastore/<name>.preferences_pb`. */
    public var storageFileName: String = EncryptedDataStoreStorage.DEFAULT_FILE_NAME

    /** Android Keystore alias of the default storage's key. */
    public var keyAlias: String = AndroidKeystoreCipher.DEFAULT_ALIAS

    /** Prefix of the storage keys; sets the client's `storagePrefix` when given (default `better-auth`). */
    public var storagePrefix: String? = null

    /** Sets the client's `disableCache` when given: skip caching the `get-session` result. */
    public var disableCache: Boolean? = null

    /** Sets the client's `cookiePrefix` when given. Must match the server's `advanced.cookiePrefix`. */
    public var cookiePrefix: String? = null
}

/** What the Android plugin adds to the client: `auth.android`. */
public class AndroidApi internal constructor(
    /** The configured deep-link scheme, if any. */
    public val scheme: String?,
) {
    /**
     * A deep link into the app: `deepLink("/dashboard")` is `myapp://dashboard`. Use it for `callbackUrl` parameters
     * (the plugin also does this automatically for relative paths).
     */
    public fun deepLink(path: String = ""): String {
        val scheme = checkNotNull(scheme) { "androidClient { scheme = \"...\" } is required to build deep links" }
        return "$scheme://${path.removePrefix("/")}"
    }
}

private val AndroidKey = PluginKey<AndroidApi>("android", "androidClient(context)")

/**
 * Registers the Android plugin, the counterpart of Better Auth's `expoClient`:
 *
 * ```kotlin
 * val auth = BetterAuthClient {
 *     baseUrl = "https://api.example.com/api/auth"
 *     plugins(androidClient(applicationContext) { scheme = "myapp" }, twoFactorClient())
 * }
 * ```
 *
 * It provides the default encrypted storage, so the session survives restarts; the deep-link origin header
 * (`expo-origin`, which the server's `expo()` plugin turns into the request origin); and relative callback URLs rewritten
 * to deep links. Social sign-in in a Custom Tab is not part of it.
 */
public fun androidClient(context: Context, configure: AndroidClientConfig.() -> Unit = {}): ClientPlugin<AndroidApi> =
    AndroidClientPlugin(AndroidClientConfig().apply(configure)) { config ->
        EncryptedDataStoreStorage.create(context, config.storageFileName, config.keyAlias)
    }

/** The Android plugin's API. Throws if [androidClient] was not registered with `plugins(...)`. */
public val BetterAuthClient.android: AndroidApi get() = plugin(AndroidKey)

internal class AndroidClientPlugin(
    private val options: AndroidClientConfig,
    private val defaultStorage: (AndroidClientConfig) -> KeyValueStorage,
) : ClientPlugin<AndroidApi> {
    override val key: PluginKey<AndroidApi> = AndroidKey

    override fun configure(config: BetterAuthConfig) {
        options.storagePrefix?.let { config.storagePrefix = it }
        options.disableCache?.let { config.disableCache = it }
        options.cookiePrefix?.let { config.cookiePrefix = it }
        if (config.storage == null && config.sessionStore == null) {
            config.storage = options.storage ?: defaultStorage(options)
        }
    }

    override val hooks: PluginHooks = PluginHooks(onRequest = { request ->
        // Mirrors the Expo plugin: skip the OAuth proxy, add the deep-link origin and rewrite relative callback URLs,
        // except for native ID-token sign-ins, which never go through a browser.
        request.headers["x-skip-oauth-proxy"] = "true"
        val body = request.body as? JsonObject
        val isIdToken = body?.containsKey("idToken") == true
        val scheme = options.scheme
        if (!isIdToken && scheme != null) {
            request.headers["expo-origin"] = "$scheme://"
            if (body != null) request.body = JsonObject(body.mapValues { (key, value) ->
                if (key in CALLBACK_FIELDS) value.rewritten(scheme) else value
            })
        }
    })

    override fun createApi(context: PluginContext): AndroidApi = AndroidApi(options.scheme)

    private fun kotlinx.serialization.json.JsonElement.rewritten(scheme: String): kotlinx.serialization.json.JsonElement {
        val text = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return this
        return if (text.startsWith("/")) JsonPrimitive("$scheme://${text.removePrefix("/")}") else this
    }

    private companion object {
        val CALLBACK_FIELDS = setOf("callbackURL", "newUserCallbackURL", "errorCallbackURL")
    }
}

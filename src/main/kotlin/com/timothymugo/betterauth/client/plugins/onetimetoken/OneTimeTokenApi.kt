package com.timothymugo.betterauth.client.plugins.onetimetoken

import com.timothymugo.betterauth.client.BetterAuthClient
import com.timothymugo.betterauth.client.api.*
import com.timothymugo.betterauth.client.plugin.ClientPlugin
import com.timothymugo.betterauth.client.plugin.PluginContext
import com.timothymugo.betterauth.client.plugin.PluginKey

import com.timothymugo.betterauth.client.http.Transport
import com.timothymugo.betterauth.client.model.AuthResponse
import com.timothymugo.betterauth.client.model.jsonBody
import com.timothymugo.betterauth.client.model.str
import com.timothymugo.betterauth.client.result.BetterAuthResult

/** `client.oneTimeToken`: hand a session to another client (for example a web view or a second app). */
public class OneTimeTokenApi internal constructor(private val t: Transport) {
    /** Creates a single-use token for the signed-in session. */
    public suspend fun generate(): BetterAuthResult<String> =
        t.get("/one-time-token/generate") { it.asObject().str("token") ?: error("`token` missing in response") }

    /** Redeems a token on this client, signing it in. */
    public suspend fun verify(token: String): BetterAuthResult<AuthResponse> =
        t.post("/one-time-token/verify", jsonBody { put("token", token) }) { it.toAuthResponse() }
}

private val OneTimeTokenKey = PluginKey<OneTimeTokenApi>("oneTimeToken", "oneTimeTokenClient()")

private object OneTimeTokenPlugin : ClientPlugin<OneTimeTokenApi> {
    override val key: PluginKey<OneTimeTokenApi> = OneTimeTokenKey

    override fun createApi(context: PluginContext): OneTimeTokenApi = OneTimeTokenApi(context.transport)
}

/**
 * Registers the One-time tokens plugin: `BetterAuthClient { plugins(oneTimeTokenClient()) }`. Its API is then `client.oneTimeToken`.
 */
public fun oneTimeTokenClient(): ClientPlugin<OneTimeTokenApi> = OneTimeTokenPlugin

/** The One-time tokens API. Throws if [oneTimeTokenClient] was not registered with `plugins(...)`. */
public val BetterAuthClient.oneTimeToken: OneTimeTokenApi get() = plugin(OneTimeTokenKey)

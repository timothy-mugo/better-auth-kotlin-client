package com.timothymugo.betterauth.client.plugins.multisession

import com.timothymugo.betterauth.client.BetterAuthClient
import com.timothymugo.betterauth.client.api.*
import com.timothymugo.betterauth.client.plugin.ClientPlugin
import com.timothymugo.betterauth.client.plugin.PluginContext
import com.timothymugo.betterauth.client.plugin.PluginKey

import com.timothymugo.betterauth.client.http.Transport
import com.timothymugo.betterauth.client.model.OperationResult
import com.timothymugo.betterauth.client.model.SessionData
import com.timothymugo.betterauth.client.model.jsonBody
import com.timothymugo.betterauth.client.model.obj
import com.timothymugo.betterauth.client.result.BetterAuthResult
import kotlinx.serialization.json.JsonArray

/**
 * `client.multiSession`: several signed-in accounts on one device.
 *
 * The server tracks the other sessions in `_multi-` cookies; the SDK keeps those cookies in its jar (in both auth
 * modes) so these calls work. Sessions are identified by their raw [com.timothymugo.betterauth.client.model.Session.token].
 */
public class MultiSessionApi internal constructor(private val t: Transport) {
    public suspend fun listDeviceSessions(): BetterAuthResult<List<SessionData>> =
        t.get("/multi-session/list-device-sessions") { element ->
            (element as? JsonArray).orEmpty().mapNotNull { item ->
                val o = item.asObject()
                val session = o.obj("session") ?: return@mapNotNull null
                val user = o.obj("user") ?: return@mapNotNull null
                SessionData(session.toSession(), user.toUser())
            }
        }

    /** Makes [sessionToken] the active session. The SDK picks up the new token from the response. */
    public suspend fun setActive(sessionToken: String): BetterAuthResult<SessionData> =
        t.post("/multi-session/set-active", jsonBody { put("sessionToken", sessionToken) }) { element ->
            val o = element.asObject()
            SessionData(
                session = (o.obj("session") ?: error("`session` missing in response")).toSession(),
                user = (o.obj("user") ?: error("`user` missing in response")).toUser(),
            )
        }

    public suspend fun revoke(sessionToken: String): BetterAuthResult<OperationResult> =
        t.postForResult("/multi-session/revoke", jsonBody { put("sessionToken", sessionToken) })
}

private val MultiSessionKey = PluginKey<MultiSessionApi>("multiSession", "multiSessionClient()")

private object MultiSessionPlugin : ClientPlugin<MultiSessionApi> {
    override val key: PluginKey<MultiSessionApi> = MultiSessionKey

    override fun createApi(context: PluginContext): MultiSessionApi = MultiSessionApi(context.transport)
}

/**
 * Registers the Multiple sessions on one device plugin: `BetterAuthClient { plugins(multiSessionClient()) }`. Its API is then `client.multiSession`.
 */
public fun multiSessionClient(): ClientPlugin<MultiSessionApi> = MultiSessionPlugin

/** The Multiple sessions on one device API. Throws if [multiSessionClient] was not registered with `plugins(...)`. */
public val BetterAuthClient.multiSession: MultiSessionApi get() = plugin(MultiSessionKey)

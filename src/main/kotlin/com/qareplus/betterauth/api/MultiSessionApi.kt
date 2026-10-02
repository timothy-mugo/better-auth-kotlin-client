package com.qareplus.betterauth.api

import com.qareplus.betterauth.http.Transport
import com.qareplus.betterauth.model.OperationResult
import com.qareplus.betterauth.model.SessionData
import com.qareplus.betterauth.model.jsonBody
import com.qareplus.betterauth.model.obj
import com.qareplus.betterauth.result.BetterAuthResult
import kotlinx.serialization.json.JsonArray

/**
 * `client.multiSession`: several signed-in accounts on one device.
 *
 * The server tracks the other sessions in `_multi-` cookies; the SDK keeps those cookies in its jar (in both auth
 * modes) so these calls work. Sessions are identified by their raw [com.qareplus.betterauth.model.Session.token].
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

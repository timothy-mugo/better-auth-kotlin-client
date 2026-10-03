package com.timothymugo.betterauth.client.api

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

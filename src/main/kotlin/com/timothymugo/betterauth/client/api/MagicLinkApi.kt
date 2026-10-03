package com.timothymugo.betterauth.client.api

import com.timothymugo.betterauth.client.http.Transport
import com.timothymugo.betterauth.client.model.AuthOrRedirect
import com.timothymugo.betterauth.client.result.BetterAuthResult
import io.ktor.http.HttpMethod

/** `client.magicLink`. Requesting a link is `client.signIn.magicLink(...)`. */
public class MagicLinkApi internal constructor(private val t: Transport) {
    /**
     * Verifies the token from a magic link. Without a callback URL the result carries the session; with one the
     * server answers with a redirect, returned (not followed) in [AuthOrRedirect.redirect].
     */
    public suspend fun verify(
        token: String,
        callbackUrl: String? = null,
        errorCallbackUrl: String? = null,
        newUserCallbackUrl: String? = null,
    ): BetterAuthResult<AuthOrRedirect> = t.jsonOrRedirect(
        HttpMethod.Get,
        "/magic-link/verify",
        query = mapOf(
            "token" to token,
            "callbackURL" to callbackUrl,
            "errorCallbackURL" to errorCallbackUrl,
            "newUserCallbackURL" to newUserCallbackUrl,
        ),
        onRedirect = { AuthOrRedirect(redirect = it) },
    ) { AuthOrRedirect(response = it.toAuthResponse()) }
}

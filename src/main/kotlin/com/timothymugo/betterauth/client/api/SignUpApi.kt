package com.timothymugo.betterauth.client.api

import com.timothymugo.betterauth.client.http.Transport
import com.timothymugo.betterauth.client.model.AuthResponse
import com.timothymugo.betterauth.client.model.jsonBody
import com.timothymugo.betterauth.client.result.BetterAuthResult
import kotlinx.serialization.json.JsonObject

/** `client.signUp` */
public class SignUpApi internal constructor(private val t: Transport) {
    /**
     * Creates a user with email and password.
     *
     * [additionalFields] are merged into the request body. Use them for fields your server requires or accepts on
     * sign-up (`user.additionalFields`, consent flags added by plugins, ...).
     *
     * [AuthResponse.token] is `null` when the server requires email verification before the first sign-in.
     */
    public suspend fun email(
        name: String,
        email: String,
        password: String,
        image: String? = null,
        callbackUrl: String? = null,
        rememberMe: Boolean? = null,
        additionalFields: JsonObject? = null,
    ): BetterAuthResult<AuthResponse> = t.post(
        "/sign-up/email",
        jsonBody {
            putAll(additionalFields)
            put("name", name)
            put("email", email)
            put("password", password)
            put("image", image)
            put("callbackURL", callbackUrl)
            put("rememberMe", rememberMe)
        },
    ) { it.toAuthResponse() }
}

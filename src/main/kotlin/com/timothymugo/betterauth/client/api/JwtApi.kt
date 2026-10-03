package com.timothymugo.betterauth.client.api

import com.timothymugo.betterauth.client.http.Transport
import com.timothymugo.betterauth.client.model.JsonWebKey
import com.timothymugo.betterauth.client.model.arr
import com.timothymugo.betterauth.client.model.str
import com.timothymugo.betterauth.client.result.BetterAuthResult
import kotlinx.serialization.json.JsonObject

/** `client.jwt` (JWT plugin). The latest `set-auth-jwt` header is also available as `storedSession().jwt`. */
public class JwtApi internal constructor(private val t: Transport) {
    /** A JWT for the signed-in session, for calling services that verify against [jwks]. */
    public suspend fun token(): BetterAuthResult<String> =
        t.get("/token") { it.asObject().str("token") ?: error("`token` missing in response") }

    /** The public keys to verify tokens with. Backends should cache this. */
    public suspend fun jwks(): BetterAuthResult<List<JsonWebKey>> = t.get("/jwks") { element ->
        element.asObject().arr("keys").orEmpty().map { key ->
            val o = key as JsonObject
            JsonWebKey(
                kid = o.str("kid") ?: "",
                kty = o.str("kty") ?: "",
                alg = o.str("alg"),
                use = o.str("use"),
                raw = o,
            )
        }
    }
}

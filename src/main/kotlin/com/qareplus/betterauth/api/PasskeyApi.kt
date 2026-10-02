package com.qareplus.betterauth.api

import com.qareplus.betterauth.http.Transport
import com.qareplus.betterauth.http.BetterAuthJson
import com.qareplus.betterauth.model.OperationResult
import com.qareplus.betterauth.model.Passkey
import com.qareplus.betterauth.model.PasskeyRegistration
import com.qareplus.betterauth.model.WebAuthnOptions
import com.qareplus.betterauth.model.jsonBody
import com.qareplus.betterauth.model.obj
import com.qareplus.betterauth.result.BetterAuthResult
import io.ktor.http.HttpMethod
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

/**
 * `client.passkey`
 *
 * The SDK does not model WebAuthn. Options come back as raw JSON for the platform (Android Credential Manager's
 * `CreatePublicKeyCredentialRequest` / `GetPublicKeyCredentialOption`), and the credential the platform returns is
 * passed back as raw JSON. Signing in with a passkey is `client.signIn.passkey(...)`.
 */
public class PasskeyApi internal constructor(private val t: Transport) {
    public suspend fun generateRegisterOptions(
        authenticatorAttachment: String? = null,
        name: String? = null,
        context: String? = null,
    ): BetterAuthResult<WebAuthnOptions> = t.get(
        "/passkey/generate-register-options",
        query = mapOf("authenticatorAttachment" to authenticatorAttachment, "name" to name, "context" to context),
    ) { WebAuthnOptions(it) }

    public suspend fun generateAuthenticateOptions(context: String? = null): BetterAuthResult<WebAuthnOptions> =
        t.get("/passkey/generate-authenticate-options", query = mapOf("context" to context)) { WebAuthnOptions(it) }

    /** Registers the credential produced by the platform. [response] is its JSON representation. */
    public suspend fun verifyRegistration(
        response: JsonElement,
        name: String? = null,
        createSession: Boolean? = null,
    ): BetterAuthResult<PasskeyRegistration> = t.post(
        "/passkey/verify-registration",
        jsonBody {
            put("response", response)
            put("name", name)
            put("createSession", createSession)
        },
    ) { element ->
        val o = element.asObject()
        PasskeyRegistration(
            passkey = BetterAuthJson.decodeFromJsonElement(Passkey.serializer(), o),
            session = o.obj("session")?.toSession(),
            user = o.obj("user")?.toUser(),
        )
    }

    public suspend fun listUserPasskeys(): BetterAuthResult<List<Passkey>> =
        t.call(HttpMethod.Get, "/passkey/list-user-passkeys", ListSerializer(Passkey.serializer()))

    public suspend fun deletePasskey(id: String): BetterAuthResult<OperationResult> =
        t.postForResult("/passkey/delete-passkey", jsonBody { put("id", id) })

    public suspend fun updatePasskey(id: String, name: String): BetterAuthResult<Passkey> = t.post(
        "/passkey/update-passkey",
        jsonBody {
            put("id", id)
            put("name", name)
        },
    ) { element ->
        val passkey = element.asObject().obj("passkey") ?: error("`passkey` missing in response")
        BetterAuthJson.decodeFromJsonElement(Passkey.serializer(), passkey)
    }
}

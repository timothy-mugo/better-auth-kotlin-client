package com.timothymugo.betterauth.client.plugins.passkey

import com.timothymugo.betterauth.client.BetterAuthClient
import com.timothymugo.betterauth.client.api.*
import com.timothymugo.betterauth.client.plugin.ClientPlugin
import com.timothymugo.betterauth.client.plugin.PluginContext
import com.timothymugo.betterauth.client.plugin.PluginKey
import com.timothymugo.betterauth.client.model.SignInOutcome
import kotlinx.serialization.json.JsonObject

import com.timothymugo.betterauth.client.http.Transport
import com.timothymugo.betterauth.client.http.BetterAuthJson
import com.timothymugo.betterauth.client.model.OperationResult
import com.timothymugo.betterauth.client.model.Passkey
import com.timothymugo.betterauth.client.model.PasskeyRegistration
import com.timothymugo.betterauth.client.model.WebAuthnOptions
import com.timothymugo.betterauth.client.model.jsonBody
import com.timothymugo.betterauth.client.model.obj
import com.timothymugo.betterauth.client.result.BetterAuthResult
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

    internal suspend fun signIn(response: JsonElement): BetterAuthResult<SignInOutcome> =
        t.post("/passkey/verify-authentication", jsonBody { put("response", response) }) { it.toSignInOutcome() }
}

private val PasskeyKey = PluginKey<PasskeyApi>("passkey", "passkeyClient()")

private object PasskeyPlugin : ClientPlugin<PasskeyApi> {
    override val key: PluginKey<PasskeyApi> = PasskeyKey

    override fun createApi(context: PluginContext): PasskeyApi = PasskeyApi(context.transport)
}

/**
 * Registers the Passkeys (WebAuthn) plugin: `BetterAuthClient { plugins(passkeyClient()) }`. Its API is then `client.passkey`.
 */
public fun passkeyClient(): ClientPlugin<PasskeyApi> = PasskeyPlugin

/** The Passkeys (WebAuthn) API. Throws if [passkeyClient] was not registered with `plugins(...)`. */
public val BetterAuthClient.passkey: PasskeyApi get() = plugin(PasskeyKey)

/**
 * Signs in with a passkey. [response] is the credential the platform returned for the options from
 * `client.passkey.generateAuthenticateOptions()`.
 */
public suspend fun SignInApi.passkey(response: JsonElement): BetterAuthResult<SignInOutcome> =
    client.passkey.signIn(response)

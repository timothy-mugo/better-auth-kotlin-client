package com.timothymugo.betterauth.client.plugins.anonymous

import com.timothymugo.betterauth.client.BetterAuthClient
import com.timothymugo.betterauth.client.api.*
import com.timothymugo.betterauth.client.plugin.ClientPlugin
import com.timothymugo.betterauth.client.plugin.PluginContext
import com.timothymugo.betterauth.client.plugin.PluginKey
import com.timothymugo.betterauth.client.model.SignInOutcome
import com.timothymugo.betterauth.client.model.jsonBody
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

import com.timothymugo.betterauth.client.http.Transport
import com.timothymugo.betterauth.client.model.OperationResult
import com.timothymugo.betterauth.client.result.BetterAuthResult
import com.timothymugo.betterauth.client.result.BetterAuthResult.Success

/** `client.anonymous`. Creating a guest user is `client.signIn.anonymous()`. */
public class AnonymousApi internal constructor(private val t: Transport) {
    /** Deletes the signed-in anonymous user and forgets the local session. */
    public suspend fun deleteUser(): BetterAuthResult<OperationResult> {
        val result = t.postForResult("/delete-anonymous-user")
        if (result is Success && result.value.success) t.store.clear()
        return result
    }

    internal suspend fun signIn(): BetterAuthResult<SignInOutcome> =
        t.post("/sign-in/anonymous") { it.toSignInOutcome() }
}

private val AnonymousKey = PluginKey<AnonymousApi>("anonymous", "anonymousClient()")

private object AnonymousPlugin : ClientPlugin<AnonymousApi> {
    override val key: PluginKey<AnonymousApi> = AnonymousKey

    override fun createApi(context: PluginContext): AnonymousApi = AnonymousApi(context.transport)
}

/**
 * Registers the Anonymous (guest) users plugin: `BetterAuthClient { plugins(anonymousClient()) }`. Its API is then `client.anonymous`.
 */
public fun anonymousClient(): ClientPlugin<AnonymousApi> = AnonymousPlugin

/** The Anonymous (guest) users API. Throws if [anonymousClient] was not registered with `plugins(...)`. */
public val BetterAuthClient.anonymous: AnonymousApi get() = plugin(AnonymousKey)

/** Creates a guest user (anonymous plugin). */
public suspend fun SignInApi.anonymous(): BetterAuthResult<SignInOutcome> = client.anonymous.signIn()

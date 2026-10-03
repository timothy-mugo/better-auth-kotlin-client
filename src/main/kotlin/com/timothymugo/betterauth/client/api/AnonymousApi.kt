package com.timothymugo.betterauth.client.api

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
}

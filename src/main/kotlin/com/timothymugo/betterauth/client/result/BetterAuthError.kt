package com.timothymugo.betterauth.client.result

import kotlinx.serialization.json.JsonElement

/**
 * Everything that can go wrong with a Better Auth call. API failures are values, not exceptions;
 * use [BetterAuthResult.getOrThrow] to turn one into a [BetterAuthException].
 */
public sealed class BetterAuthError {
    public abstract val message: String

    /**
     * The server answered with a non-2xx status.
     *
     * @property status HTTP status code.
     * @property code Machine readable Better Auth code such as `INVALID_EMAIL_OR_PASSWORD`, if the server sent one.
     * @property body The decoded JSON error body, if any.
     */
    public data class Api(
        val status: Int,
        val code: String?,
        override val message: String,
        val body: JsonElement? = null,
    ) : BetterAuthError()

    /** The request never produced an HTTP response (DNS, TLS, timeout, connection reset, ...). */
    public data class Network(val cause: Throwable) : BetterAuthError() {
        override val message: String get() = cause.message ?: cause::class.simpleName ?: "Network error"
    }

    /** The server answered 2xx but the body did not match the expected shape. */
    public data class Decoding(val cause: Throwable, val rawBody: String?) : BetterAuthError() {
        override val message: String get() = "Could not decode response: ${cause.message}"
    }
}

/** Thrown by [BetterAuthResult.getOrThrow]. */
public class BetterAuthException(public val error: BetterAuthError) : RuntimeException(error.message) {
    init {
        when (error) {
            is BetterAuthError.Network -> initCause(error.cause)
            is BetterAuthError.Decoding -> initCause(error.cause)
            is BetterAuthError.Api -> Unit
        }
    }
}

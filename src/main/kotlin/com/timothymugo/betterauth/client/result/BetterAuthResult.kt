package com.timothymugo.betterauth.client.result

/** Outcome of a Better Auth call. */
public sealed interface BetterAuthResult<out T> {
    public data class Success<out T>(val value: T) : BetterAuthResult<T>

    public data class Failure(val error: BetterAuthError) : BetterAuthResult<Nothing>

    public val isSuccess: Boolean get() = this is Success

    public fun getOrNull(): T? = (this as? Success)?.value

    public fun errorOrNull(): BetterAuthError? = (this as? Failure)?.error

    /** Returns the value or throws [BetterAuthException]. */
    public fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw BetterAuthException(error)
    }
}

public inline fun <T, R> BetterAuthResult<T>.map(transform: (T) -> R): BetterAuthResult<R> = when (this) {
    is BetterAuthResult.Success -> BetterAuthResult.Success(transform(value))
    is BetterAuthResult.Failure -> this
}

public inline fun <T, R> BetterAuthResult<T>.flatMap(transform: (T) -> BetterAuthResult<R>): BetterAuthResult<R> =
    when (this) {
        is BetterAuthResult.Success -> transform(value)
        is BetterAuthResult.Failure -> this
    }

public inline fun <T, R> BetterAuthResult<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (BetterAuthError) -> R,
): R = when (this) {
    is BetterAuthResult.Success -> onSuccess(value)
    is BetterAuthResult.Failure -> onFailure(error)
}

public inline fun <T> BetterAuthResult<T>.onSuccess(action: (T) -> Unit): BetterAuthResult<T> {
    if (this is BetterAuthResult.Success) action(value)
    return this
}

public inline fun <T> BetterAuthResult<T>.onFailure(action: (BetterAuthError) -> Unit): BetterAuthResult<T> {
    if (this is BetterAuthResult.Failure) action(error)
    return this
}

package com.qareplus.betterauth.api

import com.qareplus.betterauth.http.BetterAuthJson
import com.qareplus.betterauth.http.RawResponse
import com.qareplus.betterauth.http.Transport
import com.qareplus.betterauth.model.AuthResponse
import com.qareplus.betterauth.model.EmptyObject
import com.qareplus.betterauth.model.OperationResult
import com.qareplus.betterauth.model.PermissionCheck
import com.qareplus.betterauth.model.Permissions
import com.qareplus.betterauth.model.RedirectResponse
import com.qareplus.betterauth.model.Session
import com.qareplus.betterauth.model.User
import com.qareplus.betterauth.model.arr
import com.qareplus.betterauth.model.bool
import com.qareplus.betterauth.model.obj
import com.qareplus.betterauth.model.str
import com.qareplus.betterauth.result.BetterAuthError
import com.qareplus.betterauth.result.BetterAuthResult
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

internal fun <T> mapBody(raw: RawResponse, map: (JsonElement) -> T): BetterAuthResult<T> = try {
    val element = if (raw.bodyText.isBlank()) JsonNull else BetterAuthJson.parseToJsonElement(raw.bodyText)
    BetterAuthResult.Success(map(element))
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    BetterAuthResult.Failure(BetterAuthError.Decoding(e, raw.bodyText))
}

/** Sends a request and maps the JSON body by hand. */
internal suspend fun <T> Transport.json(
    method: HttpMethod,
    path: String,
    query: Map<String, String?> = emptyMap(),
    body: JsonElement? = null,
    map: (JsonElement) -> T,
): BetterAuthResult<T> = when (val raw = execute(method, path, query, body)) {
    is BetterAuthResult.Failure -> raw
    is BetterAuthResult.Success -> mapBody(raw.value, map)
}

internal suspend fun Transport.get(
    path: String,
    query: Map<String, String?> = emptyMap(),
): BetterAuthResult<JsonElement> = json(HttpMethod.Get, path, query, null) { it }

internal suspend fun <T> Transport.get(
    path: String,
    query: Map<String, String?> = emptyMap(),
    map: (JsonElement) -> T,
): BetterAuthResult<T> = json(HttpMethod.Get, path, query, null, map)

internal suspend fun <T> Transport.post(
    path: String,
    body: JsonElement? = null,
    map: (JsonElement) -> T,
): BetterAuthResult<T> = json(HttpMethod.Post, path, emptyMap(), body, map)

internal suspend fun Transport.postForResult(path: String, body: JsonElement? = null): BetterAuthResult<OperationResult> =
    json(HttpMethod.Post, path, emptyMap(), body) { it.toOperationResult() }

internal suspend fun Transport.getForResult(path: String, query: Map<String, String?> = emptyMap()): BetterAuthResult<OperationResult> =
    json(HttpMethod.Get, path, query, null) { it.toOperationResult() }

internal suspend fun <T> Transport.call(
    method: HttpMethod,
    path: String,
    deserializer: DeserializationStrategy<T>,
    query: Map<String, String?> = emptyMap(),
    body: JsonElement? = null,
): BetterAuthResult<T> = json(method, path, query, body) { BetterAuthJson.decodeFromJsonElement(deserializer, it) }

/** For endpoints that answer with a redirect when given a callback URL and with JSON otherwise. */
internal suspend fun <T> Transport.jsonOrRedirect(
    method: HttpMethod,
    path: String,
    query: Map<String, String?> = emptyMap(),
    body: JsonElement? = null,
    onRedirect: (RedirectResponse) -> T,
    map: (JsonElement) -> T,
): BetterAuthResult<T> = when (val raw = execute(method, path, query, body)) {
    is BetterAuthResult.Failure -> raw
    is BetterAuthResult.Success ->
        if (raw.value.isRedirect) BetterAuthResult.Success(onRedirect(RedirectResponse(raw.value.status, raw.value.location)))
        else mapBody(raw.value, map)
}

// --- element -> model helpers -------------------------------------------------------------------------------------

internal fun JsonElement.toUser(): User = BetterAuthJson.decodeFromJsonElement(User.serializer(), this)

internal fun JsonElement.toSession(): Session = BetterAuthJson.decodeFromJsonElement(Session.serializer(), this)

internal fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: EmptyObject

internal fun JsonElement.toSessionList(): List<Session> = (this as? JsonArray).orEmpty().map { it.toSession() }

/** `{status: true}` / `{success: true}` / anything else 2xx counts as success. */
internal fun JsonElement.toOperationResult(): OperationResult {
    val o = this as? JsonObject ?: return OperationResult(success = true)
    return OperationResult(success = o.bool("status") ?: o.bool("success") ?: true, message = o.str("message"))
}

/** Reads `user`, `session` and `token` from any of the shapes Better Auth uses for authentication responses. */
internal fun JsonElement.toAuthResponse(): AuthResponse {
    val o = asObject()
    val session = o.obj("session")?.let { it.toSessionOrNull() }
    return AuthResponse(
        user = o.obj("user")?.toUser(),
        token = o.str("token") ?: session?.token?.takeIf { it.isNotEmpty() },
        session = session,
    )
}

private fun JsonObject.toSessionOrNull(): Session? = runCatching { toSession() }.getOrNull()

internal fun JsonObject.strings(key: String): List<String> =
    arr(key)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

// --- organization / admin helpers ---------------------------------------------------------------------------------

internal inline fun <reified T> JsonElement.decodeAs(): T = BetterAuthJson.decodeFromJsonElement(serializer<T>(), this)

/** Admin endpoints answer either `{user}` or the bare user, depending on the endpoint and server version. */
internal fun JsonElement.toUserFlexible(): User {
    val o = asObject()
    return (o.obj("user") ?: o).toUser()
}

internal fun JsonElement.toUserOrNull(): User? {
    if (this is JsonNull) return null
    val o = asObject()
    val candidate = o.obj("user") ?: o.takeIf { it.containsKey("id") } ?: return null
    return candidate.toUser()
}

internal fun Permissions.toJson(): JsonObject = JsonObject(mapValues { (_, actions) -> JsonArray(actions.map { JsonPrimitive(it) }) })

/** One role as a string, several as an array; that is how Better Auth takes `role`. */
internal fun rolesToJson(roles: List<String>): JsonElement =
    if (roles.size == 1) JsonPrimitive(roles.single())
    else JsonArray(roles.map { JsonPrimitive(it) })

internal fun JsonElement.toPermissionCheck(): PermissionCheck {
    val o = asObject()
    return PermissionCheck(o.bool("success") ?: false, o.str("error"))
}

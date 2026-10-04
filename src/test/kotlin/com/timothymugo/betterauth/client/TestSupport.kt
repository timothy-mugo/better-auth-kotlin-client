package com.timothymugo.betterauth.client

import com.timothymugo.betterauth.client.plugins.twofactor.twoFactorClient
import com.timothymugo.betterauth.client.plugins.emailotp.emailOtpClient
import com.timothymugo.betterauth.client.plugins.phonenumber.phoneNumberClient
import com.timothymugo.betterauth.client.plugins.magiclink.magicLinkClient
import com.timothymugo.betterauth.client.plugins.anonymous.anonymousClient
import com.timothymugo.betterauth.client.plugins.onetimetoken.oneTimeTokenClient
import com.timothymugo.betterauth.client.plugins.multisession.multiSessionClient
import com.timothymugo.betterauth.client.plugins.jwt.jwtClient
import com.timothymugo.betterauth.client.plugins.passkey.passkeyClient
import com.timothymugo.betterauth.client.plugins.admin.adminClient
import com.timothymugo.betterauth.client.plugins.organization.organizationClient
import com.timothymugo.betterauth.client.config.BetterAuthConfig
import com.timothymugo.betterauth.client.http.AuthMode
import com.timothymugo.betterauth.client.result.BetterAuthError
import com.timothymugo.betterauth.client.result.BetterAuthResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertIs

const val BASE = "https://auth.example.com/api/auth"

fun allPlugins(): Array<com.timothymugo.betterauth.client.plugin.ClientPlugin<*>> = arrayOf(
    twoFactorClient(), emailOtpClient(), phoneNumberClient(), magicLinkClient(), anonymousClient(), oneTimeTokenClient(),
    multiSessionClient(), jwtClient(), passkeyClient(), adminClient(), organizationClient(),
)

class TestServer(private val handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val requests = mutableListOf<HttpRequestData>()
    val last: HttpRequestData get() = requests.last()

    val engine = MockEngine { request ->
        requests += request
        handler(request)
    }

    /** A client with every plugin registered, unless [withPlugins] is false. */
    fun client(
        mode: AuthMode = AuthMode.Bearer,
        withPlugins: Boolean = true,
        configure: BetterAuthConfig.() -> Unit = {},
    ): BetterAuthClient = BetterAuthClient {
        baseUrl = BASE
        this.mode = mode
        engine = this@TestServer.engine
        if (withPlugins) plugins(*allPlugins())
        configure()
    }
}

fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    vararg headers: Pair<String, List<String>>,
): HttpResponseData = respond(
    content = body,
    status = status,
    headers = headersOf(
        HttpHeaders.ContentType to listOf("application/json"),
        *headers,
    ),
)

fun HttpRequestData.bodyJson(): JsonObject =
    Json.parseToJsonElement((body as TextContent).text).jsonObject

fun HttpRequestData.bodyElement(): JsonElement = Json.parseToJsonElement((body as TextContent).text)

fun HttpRequestData.header(name: String): String? = headers[name]

val HttpRequestData.path: String get() = url.encodedPath

const val USER_JSON = """{"id":"u1","name":"Ada","email":"ada@example.com","emailVerified":true,
 "createdAt":"2026-01-02T03:04:05.000Z","updatedAt":"2026-01-02T03:04:05.000Z",
 "isProvider":true,"countryId":"ke","dateOfBirth":"1990-01-01"}"""

const val SESSION_JSON = """{"id":"s1","token":"tok-abc","userId":"u1","expiresAt":"2026-02-01T00:00:00.000Z",
 "createdAt":"2026-01-02T03:04:05.000Z","updatedAt":"2026-01-02T03:04:05.000Z","ipAddress":"1.2.3.4","userAgent":"ua",
 "activeOrganizationId":"org1"}"""

inline fun <reified T> BetterAuthResult<*>.success(): T {
    if (this is BetterAuthResult.Failure) error("Expected success but was $error")
    val value = (this as BetterAuthResult.Success<*>).value
    assertIs<T>(value)
    return value
}

fun BetterAuthResult<*>.failure(): BetterAuthError = (this as? BetterAuthResult.Failure)?.error
    ?: error("Expected failure but was $this")

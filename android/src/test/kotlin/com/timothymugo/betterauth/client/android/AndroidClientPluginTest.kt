package com.timothymugo.betterauth.client.android

import com.timothymugo.betterauth.client.BetterAuthClient
import com.timothymugo.betterauth.client.session.StorageSessionStore
import com.timothymugo.betterauth.client.storage.InMemoryStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The plugin on the JVM, with a fake storage instead of the Keystore (that part is in the instrumented tests). */
class AndroidClientPluginTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val engine = MockEngine { request ->
        requests += request
        respond("""{"ok":true}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }
    private val last get() = requests.last()
    private fun body(): JsonObject = Json.parseToJsonElement((last.body as TextContent).text).jsonObject

    private fun plugin(
        storage: InMemoryStorage = InMemoryStorage(),
        configure: AndroidClientConfig.() -> Unit = {},
    ) = AndroidClientPlugin(AndroidClientConfig().apply(configure)) { storage }

    private fun client(plugin: AndroidClientPlugin, configure: com.timothymugo.betterauth.client.config.BetterAuthConfig.() -> Unit = {}) =
        BetterAuthClient {
            baseUrl = "https://auth.example.com/api/auth"
            engine = this@AndroidClientPluginTest.engine
            plugins(plugin)
            configure()
        }

    // --- storage --------------------------------------------------------------------------------------------------

    @Test
    fun `the default storage persists the session`() = runBlocking {
        val storage = InMemoryStorage()
        client(plugin(storage)).restoreToken("persisted")
        assertEquals("persisted", StorageSessionStore(storage).get().token)
    }

    @Test
    fun `a configured storage wins over the default`() = runBlocking {
        val default = InMemoryStorage()
        val mine = InMemoryStorage()
        client(plugin(default) { storage = mine }).restoreToken("t")
        assertEquals("t", StorageSessionStore(mine).get().token)
        assertTrue(StorageSessionStore(default).get().isEmpty)
    }

    @Test
    fun `the client's own storage is left alone`() = runBlocking {
        val default = InMemoryStorage()
        val own = InMemoryStorage()
        client(plugin(default)) { storage = own }.restoreToken("t")
        assertEquals("t", StorageSessionStore(own).get().token)
        assertTrue(StorageSessionStore(default).get().isEmpty)
    }

    @Test
    fun `the default storage is not even created when the client has a storage`() {
        var created = 0
        val plugin = AndroidClientPlugin(AndroidClientConfig()) { created++; InMemoryStorage() }
        client(plugin) { storage = InMemoryStorage() }
        assertEquals(0, created)
    }

    @Test
    fun `storagePrefix, disableCache and cookiePrefix are applied to the client`() = runBlocking {
        val storage = InMemoryStorage()
        val auth = client(plugin(storage) { storagePrefix = "myapp"; disableCache = true; cookiePrefix = "myapp" })
        auth.restoreToken("t")
        assertEquals("t", StorageSessionStore(storage, "myapp").get().token)
        assertNull(auth.cachedSession(), "the cache is disabled")
    }

    // --- hooks -----------------------------------------------------------------------------------------------------

    @Test
    fun `sends the deep-link origin and skips the oauth proxy`() = runBlocking {
        client(plugin { scheme = "myapp" }).ok()
        assertEquals("myapp://", last.headers["expo-origin"])
        assertEquals("true", last.headers["x-skip-oauth-proxy"])
    }

    @Test
    fun `relative callback URLs become deep links, absolute ones are untouched`() = runBlocking {
        val auth = client(plugin { scheme = "myapp" })
        auth.request(
            "POST", "/sign-in/social",
            buildJsonObject {
                put("provider", "google")
                put("callbackURL", "/dashboard")
                put("newUserCallbackURL", "/welcome/start")
                put("errorCallbackURL", "https://web.example.com/error")
                put("name", "/not-a-callback")
            },
        )
        val sent = body()
        assertEquals(JsonPrimitive("myapp://dashboard"), sent["callbackURL"])
        assertEquals(JsonPrimitive("myapp://welcome/start"), sent["newUserCallbackURL"])
        assertEquals(JsonPrimitive("https://web.example.com/error"), sent["errorCallbackURL"])
        assertEquals(JsonPrimitive("/not-a-callback"), sent["name"], "only the callback fields are rewritten")
        assertEquals(JsonPrimitive("google"), sent["provider"])
    }

    @Test
    fun `native id-token sign-in is not rewritten and gets no origin header`() = runBlocking {
        val auth = client(plugin { scheme = "myapp" })
        auth.request(
            "POST", "/sign-in/social",
            buildJsonObject {
                put("provider", "google")
                put("callbackURL", "/dashboard")
                put("idToken", buildJsonObject { put("token", "jwt") })
            },
        )
        assertEquals(JsonPrimitive("/dashboard"), body()["callbackURL"])
        assertNull(last.headers["expo-origin"])
        assertEquals("true", last.headers["x-skip-oauth-proxy"])
    }

    @Test
    fun `without a scheme only the oauth-proxy header and storage are provided`() = runBlocking {
        val auth = client(plugin())
        auth.request("POST", "/sign-in/social", buildJsonObject { put("callbackURL", "/dashboard") })
        assertEquals(JsonPrimitive("/dashboard"), body()["callbackURL"])
        assertNull(last.headers["expo-origin"])
        assertEquals("true", last.headers["x-skip-oauth-proxy"])
    }

    @Test
    fun `GET requests and requests without a JSON object body are handled`() = runBlocking {
        val auth = client(plugin { scheme = "myapp" })
        auth.ok()
        assertEquals("GET", last.method.value)
        assertEquals("myapp://", last.headers["expo-origin"])
    }

    // --- API -------------------------------------------------------------------------------------------------------

    @Test
    fun `deepLink builds app links`() {
        val auth = client(plugin { scheme = "myapp" })
        val api = auth.pluginOrNull(androidKey())!!
        assertEquals("myapp://dashboard", api.deepLink("/dashboard"))
        assertEquals("myapp://dashboard", api.deepLink("dashboard"))
        assertEquals("myapp://", api.deepLink())
        assertEquals("myapp", api.scheme)
    }

    @Test
    fun `deepLink needs a scheme`() {
        val api = client(plugin()).pluginOrNull(androidKey())!!
        assertFailsWith<IllegalStateException> { api.deepLink("/x") }
    }

    @Test
    fun `the plugin is not there unless registered`() {
        val auth = BetterAuthClient { baseUrl = "https://auth.example.com/api/auth"; engine = this@AndroidClientPluginTest.engine }
        val e = assertFailsWith<IllegalStateException> { auth.android }
        assertTrue(e.message!!.contains("androidClient(context)"))
    }

    private fun androidKey() = AndroidClientPlugin(AndroidClientConfig()) { InMemoryStorage() }.key
}

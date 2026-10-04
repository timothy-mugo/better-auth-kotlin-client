package com.timothymugo.betterauth.client

import com.timothymugo.betterauth.client.config.BetterAuthConfig
import com.timothymugo.betterauth.client.plugin.ClientPlugin
import com.timothymugo.betterauth.client.plugin.PluginContext
import com.timothymugo.betterauth.client.plugin.PluginHooks
import com.timothymugo.betterauth.client.plugin.PluginKey
import com.timothymugo.betterauth.client.plugin.RequestContext
import com.timothymugo.betterauth.client.plugin.ResponseContext
import com.timothymugo.betterauth.client.plugin.request
import com.timothymugo.betterauth.client.plugins.magiclink.magicLink
import com.timothymugo.betterauth.client.plugins.magiclink.magicLinkClient
import com.timothymugo.betterauth.client.plugins.organization.organization
import com.timothymugo.betterauth.client.plugins.passkey.passkey
import com.timothymugo.betterauth.client.plugins.twofactor.twoFactor
import com.timothymugo.betterauth.client.plugins.twofactor.twoFactorClient
import com.timothymugo.betterauth.client.result.BetterAuthResult
import com.timothymugo.betterauth.client.session.StorageSessionStore
import com.timothymugo.betterauth.client.storage.InMemoryStorage
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val PingKey = PluginKey<PingApi>("ping", "pingClient()")

@Serializable
private data class Pong(val pong: String)

/** A plugin written outside the library, using only the public plugin API. */
private class PingApi(private val context: PluginContext, val creation: Int) {
    suspend fun ping(message: String): BetterAuthResult<Pong> =
        context.request<Pong>("POST", "/ping/echo", body = buildJsonObject { put("message", message) })
}

private class PingPlugin(
    override val hooks: PluginHooks = PluginHooks(),
    private val onConfigure: (BetterAuthConfig) -> Unit = {},
) : ClientPlugin<PingApi> {
    var created = 0
        private set

    override val key: PluginKey<PingApi> get() = PingKey

    override fun configure(config: BetterAuthConfig) = onConfigure(config)

    override fun createApi(context: PluginContext): PingApi = PingApi(context, ++created)
}

private val BetterAuthClient.ping: PingApi get() = plugin(PingKey)

class PluginMechanicsTest {
    private val ok = TestServer { json("""{"pong":"hi"}""") }

    // --- registration ----------------------------------------------------------------------------------------------

    @Test
    fun `an unregistered plugin fails with a message that says what to add`() {
        val auth = ok.client(withPlugins = false)
        val e = assertFailsWith<IllegalStateException> { auth.twoFactor }
        assertEquals("Plugin 'twoFactor' is not installed. Add twoFactorClient() to plugins(...) when building the client.", e.message)
        assertTrue(assertFailsWith<IllegalStateException> { auth.organization }.message!!.contains("organizationClient()"))
    }

    @Test
    fun `plugin contributions to the signIn namespace need the plugin too`() = runTest {
        val auth = ok.client(withPlugins = false)
        val e = assertFailsWith<IllegalStateException> { auth.signIn.magicLink("a@b.co") }
        assertTrue(e.message!!.contains("magicLinkClient()"))
        assertTrue(assertFailsWith<IllegalStateException> { auth.signIn.passkey(JsonObject(emptyMap())) }.message!!.contains("passkeyClient()"))
        assertTrue(ok.requests.isEmpty(), "nothing may be sent for an unregistered plugin")
    }

    @Test
    fun `only the registered plugins exist`() = runTest {
        val auth = ok.client(withPlugins = false) { plugins(twoFactorClient()) }
        assertTrue(auth.pluginOrNull(PingKey) == null)
        auth.twoFactor.sendOtp()          // registered: works
        assertFailsWith<IllegalStateException> { auth.organization }
    }

    @Test
    fun `core calls need no plugin at all`() = runTest {
        val server = TestServer { json("""{"redirect":false,"token":"t","user":$USER_JSON}""") }
        val auth = server.client(withPlugins = false)
        auth.signIn.email("a@b.co", "pw").success<com.timothymugo.betterauth.client.model.SignInOutcome.Authenticated>()
        assertEquals("/api/auth/sign-in/email", server.last.path)
    }

    @Test
    fun `registering a plugin twice is an error`() {
        val e = assertFailsWith<IllegalArgumentException> {
            ok.client(withPlugins = false) { plugins(twoFactorClient(), magicLinkClient(), twoFactorClient()) }
        }
        assertEquals("Plugin 'twoFactor' is registered twice. Register twoFactorClient() once.", e.message)
    }

    @Test
    fun `plugins can be registered in several calls`() = runTest {
        val auth = ok.client(withPlugins = false) {
            plugins(twoFactorClient())
            plugins(magicLinkClient())
        }
        assertTrue(auth.pluginOrNull(PingKey) == null)
        auth.twoFactor
        auth.magicLink
    }

    // --- lifecycle -------------------------------------------------------------------------------------------------

    @Test
    fun `a plugin's API is created lazily, once per client view`() = runTest {
        val plugin = PingPlugin()
        val auth = ok.client(withPlugins = false) { plugins(plugin) }
        assertEquals(0, plugin.created, "not created until used")

        assertSame(auth.ping, auth.ping)
        assertEquals(1, plugin.created, "created once for repeated use")

        val view = auth.withSession("user-token")
        assertEquals(1, plugin.created, "a view does not create it until it is used there")
        assertNotSame(auth.ping, view.ping)
        assertEquals(2, plugin.created, "each view has its own instance")
        assertSame(view.ping, view.ping)
        assertTrue(auth.isInstalled(PingKey))
        assertFalse(auth.isInstalled(PluginKey<Any>("other", "otherClient()")))
    }

    @Test
    fun `configure runs in registration order, before the client is built`() = runTest {
        val order = mutableListOf<String>()
        val first = PingPlugin(onConfigure = { order += "first"; it.headers["x-first"] = "1" })
        val second = object : ClientPlugin<Unit> {
            override val key = PluginKey<Unit>("second", "secondClient()")
            override fun configure(config: BetterAuthConfig) { order += "second"; config.userAgent = "plugin-agent/1" }
            override fun createApi(context: PluginContext) = Unit
        }
        val auth = ok.client(withPlugins = false) { plugins(first, second) }
        assertEquals(listOf("first", "second"), order)
        auth.ping.ping("x")
        assertEquals("1", ok.last.header("x-first"))
        assertEquals("plugin-agent/1", ok.last.header("User-Agent"))
    }

    @Test
    fun `a plugin can set the default storage`() = runTest {
        val storage = InMemoryStorage()
        val plugin = PingPlugin(onConfigure = { if (it.storage == null) it.storage = storage })
        val auth = ok.client(withPlugins = false) { plugins(plugin) }
        auth.restoreToken("persisted")
        assertEquals("persisted", StorageSessionStore(storage).get().token)
    }

    // --- a plugin written outside the library -------------------------------------------------------------------

    @Test
    fun `a third-party plugin talks to the server through the plugin context`() = runTest {
        val auth = ok.client(withPlugins = false) { plugins(PingPlugin()) }
        auth.restoreToken("tok")
        val pong = auth.ping.ping("hello").success<Pong>()
        assertEquals("hi", pong.pong)
        assertEquals("/api/auth/ping/echo", ok.last.path)
        assertEquals(JsonPrimitive("hello"), ok.last.bodyJson()["message"])
        assertEquals("Bearer tok", ok.last.header("Authorization"))
    }

    // --- hooks -----------------------------------------------------------------------------------------------------

    @Test
    fun `onRequest hooks can add headers, rewrite the query and the body, in registration order`() = runTest {
        val seen = mutableListOf<String>()
        val first = PingPlugin(PluginHooks(onRequest = { r: RequestContext ->
            seen += "first:${r.method} ${r.path}"
            r.headers["x-trace"] = "a"
            r.query["lang"] = "en"
            r.body = buildJsonObject { put("message", "rewritten-by-first") }
        }))
        val second = object : ClientPlugin<Unit> {
            override val key = PluginKey<Unit>("second", "secondClient()")
            override val hooks = PluginHooks(onRequest = { r ->
                seen += "second sees ${(r.body as JsonObject)["message"]}"
                r.headers["x-trace"] = "b"
            })
            override fun createApi(context: PluginContext) = Unit
        }
        val auth = ok.client(withPlugins = false) { plugins(first, second) }
        auth.ping.ping("original")

        assertEquals(listOf("first:POST /ping/echo", "second sees \"rewritten-by-first\""), seen)
        assertEquals("b", ok.last.header("x-trace"), "the later hook has the last word")
        assertEquals("en", ok.last.url.parameters["lang"])
        assertEquals(JsonPrimitive("rewritten-by-first"), ok.last.bodyJson()["message"])
    }

    @Test
    fun `a hook cannot override the session's Authorization header`() = runTest {
        val plugin = PingPlugin(PluginHooks(onRequest = { it.headers["Authorization"] = "Bearer evil" }))
        val auth = ok.client(withPlugins = false) { plugins(plugin) }
        auth.restoreToken("real")
        auth.ping.ping("x")
        assertEquals(listOf("Bearer real"), ok.last.headers.getAll("Authorization"))
    }

    @Test
    fun `onResponse hooks see the status, path and body of every response, including errors`() = runTest {
        val seen = mutableListOf<Triple<String, Int, String?>>()
        val server = TestServer { req ->
            if (req.path.endsWith("/fail")) json("""{"code":"NOPE","message":"no"}""", HttpStatusCode.Unauthorized)
            else json("""{"pong":"hi"}""")
        }
        val plugin = PingPlugin(PluginHooks(onResponse = { r: ResponseContext ->
            seen += Triple(r.path, r.status, (r.body as? JsonObject)?.get("pong")?.let { (it as JsonPrimitive).content })
        }))
        val auth = server.client(withPlugins = false) { plugins(plugin) }
        auth.ping.ping("x")
        auth.request("GET", "/fail").failure()
        assertEquals(listOf(Triple("/ping/echo", 200, "hi"), Triple("/fail", 401, null)), seen)
    }

    @Test
    fun `onResponse runs after the SDK stored the response's token`() = runTest {
        var tokenSeenByHook: String? = null
        lateinit var auth: BetterAuthClient
        val server = TestServer { json("""{"ok":true}""", headers = arrayOf("set-auth-token" to listOf("fresh"))) }
        val plugin = PingPlugin(PluginHooks(onResponse = { tokenSeenByHook = auth.currentToken() }))
        auth = server.client(withPlugins = false) { plugins(plugin) }
        auth.ok()
        assertEquals("fresh", tokenSeenByHook)
    }

    @Test
    fun `hooks apply to scoped views too`() = runTest {
        val plugin = PingPlugin(PluginHooks(onRequest = { it.headers["x-hooked"] = "yes" }))
        val auth = ok.client(withPlugins = false) { plugins(plugin) }
        auth.withSession("t").ok()
        assertEquals("yes", ok.last.header("x-hooked"))
        auth.withStorage(InMemoryStorage(), "u").ok()
        assertEquals("yes", ok.last.header("x-hooked"))
    }

    @Test
    fun `without hooks a request is untouched`() = runTest {
        val auth = ok.client(withPlugins = false)
        auth.ok()
        assertNull(ok.last.header("x-hooked"))
        assertEquals("GET", ok.last.method.value)
    }
}

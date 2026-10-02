package com.qareplus.betterauth

import com.qareplus.betterauth.model.SignInOutcome
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Talks to a real Better Auth server. Off by default: it does nothing unless `BETTER_AUTH_TEST_URL` is set.
 *
 * ```
 * BETTER_AUTH_TEST_URL=https://api.stg.example.com/api/auth \
 * BETTER_AUTH_TEST_ORIGIN=https://app.example.com \      # optional, a trusted origin
 * BETTER_AUTH_TEST_COOKIE_PREFIX=qareplus \              # optional
 * BETTER_AUTH_TEST_EMAIL=... BETTER_AUTH_TEST_PASSWORD=... \   # optional: enables the sign-in test
 * ./gradlew test --tests '*StagingIntegrationTest*'
 * ```
 */
class StagingIntegrationTest {
    private val url = System.getenv("BETTER_AUTH_TEST_URL")
    private val email = System.getenv("BETTER_AUTH_TEST_EMAIL")
    private val password = System.getenv("BETTER_AUTH_TEST_PASSWORD")

    private fun client() = BetterAuthClient {
        baseUrl = url
        System.getenv("BETTER_AUTH_TEST_ORIGIN")?.let { origin = it }
        System.getenv("BETTER_AUTH_TEST_COOKIE_PREFIX")?.let { cookiePrefix = it }
    }

    @Test
    fun `server is reachable`() = runBlocking {
        if (url == null) return@runBlocking
        client().use { assertEquals(true, it.ok().getOrThrow()) }
    }

    @Test
    fun `sign in, read session, sign out`() = runBlocking {
        if (url == null || email == null || password == null) return@runBlocking
        client().use { auth ->
            val outcome = auth.signIn.email(email, password).getOrThrow()
            if (outcome is SignInOutcome.TwoFactorRequired) return@use // this account needs 2FA; covered by unit tests
            assertNotNull(auth.currentToken(), "bearer token should have been captured from set-auth-token")
            val session = auth.getSession().getOrThrow()
            assertNotNull(session, "session should exist after sign in")
            auth.signOut().getOrThrow()
            assertEquals(null, auth.currentToken())
        }
    }
}

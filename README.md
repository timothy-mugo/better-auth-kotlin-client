# better-auth-kt

A Kotlin client for a running [Better Auth](https://better-auth.com) server, for **Android apps** and **Kotlin/Java backends**.

- `suspend` API that returns a `BetterAuthResult` (no exceptions for API or network errors)
- Bearer-token **and** cookie-jar transports, pluggable
- Core auth plus the two-factor, email-OTP, phone-number, magic-link, anonymous, one-time-token, multi-session, JWT,
  passkey, admin and organization plugins
- Escape hatch (`client.request`) for endpoints and custom plugins the SDK does not wrap
- JVM 17 bytecode, no `java.time` (uses `kotlin.time.Instant`), so no desugaring is needed on Android

## Setup

```kotlin
val auth = BetterAuthClient {
    baseUrl = "https://api.example.com/api/auth"   // includes the handler's base path
    // origin = "myapp://"                          // optional; defaults to the origin of baseUrl (see below)
    cookiePrefix = "myapp"                          // the server's advanced.cookiePrefix (default "better-auth")
}
```

Server requirements: the `bearer()` plugin for bearer mode (the default). Cookie mode (`mode = AuthMode.Cookie`)
works without it. The SDK keeps a cookie jar in both modes, because two-factor, passkey challenges and multi-session
travel in cookies. That is also why `origin` matters: Better Auth rejects POSTs that carry cookies unless
their `Origin` is trusted. The SDK sends `Origin` = the origin of `baseUrl` by default, which Better Auth always trusts
(provided `baseUrl` is the server's configured `baseURL`). Set `origin` to a `trustedOrigins` entry such as your app
scheme if you sit behind a proxy that changes it, or `origin = ""` to send none.

## Usage

```kotlin
when (val result = auth.signIn.email("ada@example.com", "secret")) {
    is BetterAuthResult.Success -> when (val outcome = result.value) {
        is SignInOutcome.Authenticated -> println("hello ${outcome.user?.name}")
        is SignInOutcome.TwoFactorRequired -> auth.twoFactor.verifyTotp(codeFromUser)
    }
    is BetterAuthResult.Failure -> when (val e = result.error) {
        is BetterAuthError.Api -> println("${e.status} ${e.code}: ${e.message}")
        is BetterAuthError.Network -> println("offline? ${e.message}")
        is BetterAuthError.Decoding -> println("unexpected response: ${e.message}")
    }
}

val session: SessionData? = auth.getSession().getOrThrow()   // null = signed out
auth.signOut()
```

Namespaces: `signUp`, `signIn`, `twoFactor`, `emailOtp`, `phoneNumber`, `magicLink`, `anonymous`, `oneTimeToken`,
`multiSession`, `jwt`, `passkey`, `admin`, `organization`. Session, user, account and verification calls live on the
client itself (`getSession`, `updateUser`, `changePassword`, `listSessions`, `linkSocial`, ...).

### Your server's custom fields

Fields added through `user.additionalFields` / `organization.additionalFields` are not typed in the SDK. They are
sent via `additionalFields = buildJsonObject { ... }` parameters and read from `additionalFields` on the model:

```kotlin
auth.signUp.email("Ada", "ada@example.com", "pw", additionalFields = buildJsonObject {
    put("isProvider", true)
    put("tosAccepted", true)
})
user.additionalFields["countryId"]
```

### Persisting the session

The default store is in memory. Implement `SessionStore` (a `get`/`set` pair over the `@Serializable`
`StoredSession`) to persist it, for example in EncryptedSharedPreferences or DataStore, and pass it as
`sessionStore = ...`.

### Backends validating many users

Don't share one client session across users. Derive an isolated, per-token view that reuses the connection pool:

```kotlin
val result = auth.withSession(bearerTokenFromRequest).getSession()
```

### Anything not wrapped

```kotlin
auth.request("POST", "/some-plugin/action", buildJsonObject { put("x", 1) })      // JsonElement
auth.request<MyResponse>("GET", "/some-plugin/thing", query = mapOf("id" to "1")) // typed
```

## Social sign-in and passkeys

**Native (recommended on Android):** pass the provider's ID token:
`auth.signIn.social("google", idToken = IdTokenCredentials(token = jwt, nonce = n))`. For Apple's first sign-in also
pass `user = IdTokenUser(firstName, lastName, email)`.

**Browser redirect flow** (needs the server's `expo()` plugin and your deep link in `trustedOrigins`):

```kotlin
val redirect = auth.signIn.social("google", callbackUrl = "myapp://done").getOrThrow() as SocialSignInOutcome.RedirectRequired
openCustomTab(auth.authorizationProxyUrl(redirect.url))    // not redirect.url: the OAuth state cookie is in the SDK's jar
// ...when the myapp://done?cookie=... deep link fires:
auth.completeBrowserSignIn(deepLink.getQueryParameter("cookie")!!)
val session = auth.getSession()
```

Passkey options and credentials are passed through as raw JSON for Android Credential Manager.

## Development

```bash
./gradlew build
```

- Tests use Ktor's `MockEngine`; none touch the network.
- `CoverageTest` fails when a path in `src/test/resources/spec-endpoints.txt` has no SDK method. Regenerate the list
  from a new OpenAPI export with `scripts/extract-endpoints.py openapi.json`.
- `StagingIntegrationTest` talks to a real server only when `BETTER_AUTH_TEST_URL` is set (see the class comment).

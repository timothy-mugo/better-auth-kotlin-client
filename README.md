# better-auth-kt-client

A Kotlin client for a running [Better Auth](https://better-auth.com) server, for **Android apps** and **Kotlin/Java backends**.

- `suspend` API that returns a `BetterAuthResult` (no exceptions for API or network errors)
- Bearer-token **and** cookie-jar transports, pluggable
- Core auth plus the two-factor, email-OTP, phone-number, magic-link, anonymous, one-time-token, multi-session, JWT,
  passkey, admin and organization plugins
- Escape hatch (`client.request`) for endpoints and custom plugins the SDK does not wrap
- JVM 17 bytecode, no `java.time` (uses `kotlin.time.Instant`), so no desugaring is needed on Android

## Artifacts

Add only what your platform needs. The core has no Android and no Redis dependency, and the adapters don't depend on each
other.

| Artifact | For | Adds |
|---|---|---|
| `com.timothymugo:better-auth-kt-client` | everyone | the SDK |
| `com.timothymugo:better-auth-kt-client-android` | Android apps | `EncryptedDataStoreStorage` (Jetpack DataStore + Android Keystore) |
| `com.timothymugo:better-auth-kt-client-redis` | Kotlin/Java backends | `RedisStorage` (Redis and Valkey, via Lettuce) |

Package: `com.timothymugo.betterauth.client`.

## Installation (JitPack)

Releases are built by [JitPack](https://jitpack.io/#timothy-mugo/better-auth-kt-client) from git tags.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// build.gradle.kts: take the exact coordinates from the JitPack page for the release you want
dependencies {
    implementation("<group>:better-auth-kt-client:<tag>")             // everyone
    implementation("<group>:better-auth-kt-client-android:<tag>")     // Android apps
    implementation("<group>:better-auth-kt-client-redis:<tag>")       // backends
}
```

`<tag>` is the git tag, e.g. `v0.1.0`. `<group>` is `com.github.timothy-mugo` or `com.github.timothy-mugo.better-auth-kt-client`:
JitPack decides how it names the group of a multi-module build, and this has not been confirmed for this project yet. The
JitPack page for the release shows the exact coordinates, and the release workflow prints the modules JitPack reports. The
Kotlin package is `com.timothymugo.betterauth.client` either way.

## Setup

```kotlin
val auth = BetterAuthClient {
    baseUrl = "https://api.example.com/api/auth"   // includes the handler's base path
    // origin = "myapp://"                          // optional; defaults to the origin of baseUrl (see below)
    cookiePrefix = "myapp"                          // MUST match the server's advanced.cookiePrefix (default "better-auth")
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

### Persisting the session (storage adapters)

Like the Better Auth Expo plugin's `storage` option, the SDK persists through a tiny `KeyValueStorage` interface
(`getItem` / `setItem` / `removeItem`) that you pass in. It writes the cookie jar and token under `<storagePrefix>_cookie`
and the last `get-session` result under `<storagePrefix>_session_data`. Only Better Auth's own cookies (those starting
with `cookiePrefix`) are kept. The default is in memory.

```kotlin
val auth = BetterAuthClient {
    baseUrl = "https://api.example.com/api/auth"
    storage = /* an adapter below */
    storagePrefix = "better-auth"   // default
    disableCache = false            // default; see cachedSession()
}
```

**Android: encrypted DataStore.** Add `better-auth-kt-client-android` (`minSdk 23`).

```kotlin
val auth = BetterAuthClient {
    baseUrl = "https://api.example.com/api/auth"
    storage = EncryptedDataStoreStorage.create(applicationContext)
}
```

Every value is AES-256-GCM encrypted with a non-extractable key held in the Android Keystore, and stored in a DataStore file.
Data that can't be decrypted (key lost after a reinstall or restore, tampering) reads as "signed out" and is deleted. Because
Keystore keys are not backed up, exclude the file from Auto Backup so a restored file isn't left undecryptable:

```xml
<!-- res/xml/data_extraction_rules.xml (Android 12+) and res/xml/backup_rules.xml (older) -->
<exclude domain="file" path="datastore/better_auth_kt_client.preferences_pb"/>
```

The core pins OkHttp to 5.3.2 so that depending on it does not force `compileSdk` 37 on your app (OkHttp 5.5 needs 37, 5.4
needs 36). Declare a newer OkHttp yourself if you want one.

**Backend: Redis or Valkey.** Add `better-auth-kt-client-redis`. Use one storage and one prefix per end user:

```kotlin
val storage = RedisStorage.connect("redis://localhost:6379")        // or valkey, rediss:// for TLS
val perUser = auth.withStorage(storage, prefix = "user-$userId")    // isolated session, shared connection pool
perUser.getSession()
```

Pass your own Lettuce commands (`RedisStorage(connection.async(), ...)`) for Sentinel or Cluster, `ttl` to set the expiry
(default 30 days, renewed on every write), and `cipher = AesGcmStringCipher(key32Bytes)` to keep tokens unreadable inside
Redis. Views from `withStorage` over the same storage and prefix share a lock, so one per request is fine inside a
process. Across processes there is no locking: don't let two processes write the same prefix at the same time.

**Anything else:** implement `KeyValueStorage` (a database, a file, ...) and pass it as `storage`.

**Instant UI at launch:** `auth.cachedSession()` returns the last `get-session` result from storage, no network, or `null`
if there is none or it expired. Show it, then call `getSession()` to confirm. `auth.cookieHeader()` returns the stored
cookies as a `Cookie` header, like the Expo client's `getCookie()`.

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

- Core tests use Ktor's `MockEngine`, plus a local JDK HTTP server for the real OkHttp engine. None leave the machine.
- `:redis` tests run against `REDIS_URL` (default `redis://localhost:6379`) and, if set, `VALKEY_URL`; they skip when no server
  is reachable.
- `:android` has JVM tests (`./gradlew :android:testDebugUnitTest`) and instrumented tests that exercise the real Keystore
  (`./gradlew :android:connectedDebugAndroidTest`, needs an emulator or device and `local.properties` with `sdk.dir`).
- `CoverageTest` fails when a path in `src/test/resources/spec-endpoints.txt` has no SDK method. Regenerate the list
  from a new OpenAPI export with `scripts/extract-endpoints.py openapi.json`.
- `StagingIntegrationTest` talks to a real server only when `BETTER_AUTH_TEST_URL` is set (see the class comment).

## Releasing

Versions are git tags `vMAJOR.MINOR.PATCH` (optionally `-rc.1`, `-beta`, ...). Both ways below run the same gates: the full
CI (core, Redis and Valkey service containers, Android unit tests and lint, and the Keystore tests on an emulator), a build
of the exact artifacts consumers get, and `scripts/verify-artifacts.sh` (three modules present, Android has no Redis, Redis
has no AndroidX, the core pins OkHttp). Then a GitHub Release with generated notes and the jars/AARs is created, and the
workflow triggers the JitPack build and waits for it to succeed.

**Recommended:** Actions → *Release* → *Run workflow* from `main`, enter the version. The tag is created only after every check
passed. **Alternative:** `git tag v1.2.3 && git push origin v1.2.3` (the tag must be on `main`; the tag exists before the checks
finish, so JitPack could build it early).

Versions are immutable: a tag that exists is never reused. Fix forward with the next version.

`.github/workflows/ci.yml` runs on every push to `main` and every pull request (the emulator job skips pull requests).
JitPack builds public repositories for free; for a private repository it needs a paid plan, and the workflow skips the JitPack
step.

Try the release build locally:

```bash
./gradlew publishToMavenLocal -PreleaseVersion=1.2.3 -x test -Dmaven.repo.local=/tmp/m2
scripts/verify-artifacts.sh /tmp/m2 com.timothymugo 1.2.3
```

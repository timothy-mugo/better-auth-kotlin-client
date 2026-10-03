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

Releases are built by [JitPack](https://jitpack.io/#timothy-mugo/better-auth-kotlin-client) from git tags.

```kotlin
// settings.gradle.kts (or the module's build.gradle.kts)
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.timothy-mugo.better-auth-kotlin-client:better-auth-kt-client:v0.1.0")          // everyone
    implementation("com.github.timothy-mugo.better-auth-kotlin-client:better-auth-kt-client-android:v0.1.0")  // Android apps
    implementation("com.github.timothy-mugo.better-auth-kotlin-client:better-auth-kt-client-redis:v0.1.0")    // backends
}
```

Three things trip people up:
- **The group is `com.github.timothy-mugo.better-auth-kotlin-client`**, not `com.timothymugo`. JitPack only serves `com.github.<user>`
  groups; `com.timothymugo` is the group the artifacts are built with, and the name of the Kotlin package
  (`com.timothymugo.betterauth.client`), but not what you depend on.
- **The artifact is the module** (`better-auth-kt-client`, `-android`, `-redis`), not the repository name.
- **The version is the git tag, including the `v`**: `v0.1.0`, not `0.1.0`.

If a dependency doesn't resolve, the JitPack page for the release lists the exact coordinates and the build log.

**Use Kotlin 2.4 in your project.** The libraries are compiled with Kotlin 2.4, and a compiler can only read metadata one
version ahead of itself. Checked: from Kotlin 2.4.10 the Android library (AAR) and the JVM jars compile and resolve; from Kotlin
2.2 it fails with `Module was compiled with an incompatible version of Kotlin`. Kotlin 2.3 should work by that rule but is
untested. Beware that AGP 9.0's built-in Kotlin is 2.2: raise it by declaring a newer Kotlin Gradle plugin in the same build,
for example `kotlin("jvm") version "2.4.10" apply false` in the plugins block (that is what the check used).

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

Releases are done by [JReleaser](https://jreleaser.org). To release, **bump `libraryVersion` in `gradle.properties` and merge
that to `main`** (all three artifacts share that one version). The Release workflow then:

1. finds that `v<libraryVersion>` has no GitHub Release yet (a push that doesn't change the version finds it already
   exists and does nothing, so re-running is always safe);
2. runs every check: core, Redis and Valkey containers, Android unit tests and lint, and the Keystore tests on an emulator;
3. stages the artifacts and verifies them with `scripts/verify-artifacts.sh` (three modules present, Android has no Redis,
   Redis has no AndroidX, the core pins OkHttp);
4. runs JReleaser: it tags the commit `vX.Y.Z`, writes the changelog from the commit messages (conventional commits such as
   `feat:` and `fix(redis):` are grouped into Features and Fixes; other commits are listed as well), and creates the GitHub
   Release with the jars, AARs and checksums attached;
5. triggers the JitPack build of the tag and waits for it to succeed.

**Manual run:** Actions → *Release* → *Run workflow* (from `main`) does the same as a push to `main`. Tick **dry run** to see
what JReleaser would do (the changelog, the files it would upload) without creating anything.

If a run is interrupted after the tag was created but before the GitHub Release existed, re-run it: it finishes the release
on the existing tag, provided the tag is on the commit being released. If it isn't, the workflow stops and asks for a new
version, since the artifacts must match the tag.

Versions are immutable: fix forward with the next version. Pre-release versions (`0.2.0-rc.1`) are marked as pre-releases.

One-time setup: make the repository public. JitPack builds private repositories only on a paid plan, and the workflow skips the
JitPack step while the repository is private.

**Why `release/` is a separate Gradle build:** the JReleaser Gradle plugin and the Android Gradle Plugin both bundle JAXB and
break each other on a shared classpath (`NoClassDefFoundError: javax/activation/DataSource`), so JReleaser is configured in
`release/` and reads the shared values (version, owner, repository) from `gradle.properties`.

Try it locally:

```bash
./gradlew publish -x test                                   # stage artifacts in release/build/staging-deploy
scripts/verify-artifacts.sh release/build/staging-deploy com.timothymugo "$(sed -n 's/^libraryVersion=//p' gradle.properties)"
JRELEASER_GITHUB_TOKEN=<token> ./gradlew -p release jreleaserRelease --dryrun     # changes nothing
scripts/test-release-plan.sh                                # tests of the release guard
```

## License

Apache License 2.0. See [LICENSE](LICENSE).

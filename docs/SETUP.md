# Setup

## Requirements

| Tool | Version | Note |
| --- | --- | --- |
| JDK | 17 or newer | Toolchain targets JVM 17 bytecode; JDK 21 works |
| Gradle | 8.14.3 | Provided by the wrapper — do not install separately |
| Android SDK | API 35 | `compileSdk` 35, `targetSdk` 35, `minSdk` 24 |
| Android Studio | Ladybug or newer | Optional; the CLI is enough |

Versions are pinned in `gradle/libs.versions.toml`. Kotlin 2.0.21, AGP 8.7.3, KSP 2.0.21-1.0.28,
Compose BOM 2024.12.01, Room 2.6.1, Hilt 2.52.

`minSdk 24` covers essentially every Android phone in use in India. `java.time` works there
through core library desugaring, which is why `isCoreLibraryDesugaringEnabled = true` is set.

---

## Building

```bash
# The JVM half. Works with only a JDK — no Android SDK needed.
./gradlew -Pkhaata.androidModule=false :core:test

# The whole app.
./gradlew assembleDebug

# Everything.
./gradlew build
```

`-Pkhaata.androidModule=false` removes `:app` from the Gradle build entirely (see
`settings.gradle.kts`). Useful in CI for a fast correctness check, and necessary in any environment
without access to Google's Maven repository.

**No credentials are required to build or run.** Every external integration degrades to something
harmless — see the table below.

---

## Configuration

Nothing secret is committed. Values are read at build time by the `secret(key, default)` helper in
`app/build.gradle.kts`, which checks, in order:

1. `secrets.properties` in the repository root (git-ignored)
2. an environment variable of the same name
3. the default given in the call

### Creating `secrets.properties`

```properties
# All optional. Omit any line to take the default.

ADMOB_APP_ID=ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY
ADMOB_BANNER_UNIT_ID=ca-app-pub-XXXXXXXXXXXXXXXX/YYYYYYYYYY
ADMOB_INTERSTITIAL_UNIT_ID=ca-app-pub-XXXXXXXXXXXXXXXX/YYYYYYYYYY
ADMOB_REWARDED_UNIT_ID=ca-app-pub-XXXXXXXXXXXXXXXX/YYYYYYYYYY

CLOUD_AI_BASE_URL=https://your-own-backend.example/v1
CLOUD_AI_MODEL=

PRIVACY_POLICY_URL=https://your-domain.example/khaata/privacy
TERMS_URL=https://your-domain.example/khaata/terms
SUPPORT_EMAIL=support@your-domain.example
```

### What each one defaults to

| Key | Default | Behaviour when unset |
| --- | --- | --- |
| `ADMOB_APP_ID` and the three unit IDs | **Google's published test IDs** | Test ads render; no revenue, no policy risk |
| `CLOUD_AI_BASE_URL` | empty | Cloud AI reports itself unconfigured; the local engine handles everything |
| `CLOUD_AI_MODEL` | empty | Same |
| `PRIVACY_POLICY_URL`, `TERMS_URL` | `example.invalid` placeholders | The About screen links to a URL that will not resolve. **Replace before any release.** |
| `SUPPORT_EMAIL` | `support@example.invalid` | Same |

The AdMob defaults are the test unit IDs Google publishes for exactly this purpose. Shipping them
in a release build would mean an app with non-functional ads, not a policy violation — but check
`ADMOB.md` before releasing.

### No API key is stored in the app

There is deliberately no `CLOUD_AI_API_KEY`. An API key in an APK is extractable by anyone who
downloads it; embedding one would mean shipping a credential that bills to your account and can be
lifted with `unzip` and `strings`. `CLOUD_AI_BASE_URL` is expected to point at a backend **you**
control, which holds the key and authorises requests itself. See
[AI_PROVIDER.md](AI_PROVIDER.md).

---

## Signing

Release signing is optional and off unless configured. Create `keystore.properties` in the
repository root:

```properties
storeFile=/absolute/path/to/khaata-release.jks
storePassword=...
keyAlias=khaata
keyPassword=...
```

`app/build.gradle.kts` configures the release signing config only when this file exists; without
it, `assembleRelease` produces an unsigned APK rather than failing.

`.gitignore` excludes `*.jks`, `*.keystore`, `keystore.properties`, `secrets.properties` and
`google-services.json`. **Verify this before your first commit on a fork.**

---

## Play Billing in a debug build

Billing needs the app installed from a Play track (internal testing is enough) under a licence-
tested account. In a plain debug build, `PlayBillingProvider` reports `UNAVAILABLE`, the paywall
says purchases are not available, and every free feature keeps working — which is the whole app
minus the Pro extras. See [BILLING.md](BILLING.md).

## Ads in a debug build

`AppModule.provideAdProvider` binds `NoOpAdProvider` when `BuildConfig.DEBUG` is true, so no ad
SDK is initialised during development at all. To see a real (test) banner, change that one binding
locally — there is deliberately no flag for it, because a flag that turns ads on is a flag someone
eventually leaves on.

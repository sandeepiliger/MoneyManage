# Security

## Threat model

The realistic threats to a personal finance app with no server are, in order:

1. **Someone picks up an unlocked phone.** The most likely by far.
2. **A malicious app on the same device** trying to read the ledger.
3. **A lost or stolen device.**
4. **An extracted APK** being mined for embedded secrets.

There is no server to attack, no account to take over, and no credentials to steal — because the
app holds none of those things.

---

## App lock

`AppLockManager`, `BiometricAuthenticator`, `LockScreen`.

Three modes: off, device biometric (with device-credential fallback), or an app PIN.

### The PIN is never stored

Only a **PBKDF2-HMAC-SHA256** hash and its salt:

| | |
| --- | --- |
| Iterations | 120,000 |
| Salt | 16 random bytes from `SecureRandom`, per PIN |
| Key length | 256 bits |
| Stored in | `EncryptedSharedPreferences`, AES256-SIV keys / AES256-GCM values, master key in the Android keystore |

Someone with the file has a hash and a salt, and 120,000 iterations to run per guess against a
4-to-8-digit space. That is not unbreakable — a 4-digit PIN is 10,000 candidates — which is why the
backoff below exists and why biometric is offered first.

### Comparison is constant-time

`MessageDigest.isEqual`, not `==` on the byte arrays. A byte-by-byte comparison that returns early
leaks how much of a guess was correct through timing.

### Failed attempts back off

| Consecutive failures | Input blocked for |
| --- | --- |
| under 5 | — |
| 5–7 | 30 seconds |
| 8–10 | 2 minutes |
| 11+ | 10 minutes |

The counter and the lockout deadline are persisted, so force-quitting the app does not reset them.

**There is no data wipe on repeated failure.** Someone's child mashing the keypad should not
destroy their financial history, and an attacker with the device can copy the storage before
guessing anyway — so the feature would cost real users their data while stopping nobody.

### Turning the lock off clears the PIN

`SettingsViewModel.setLockMode(OFF)` calls `clearPin()`. Re-enabling always asks for a new one
rather than silently reviving a PIN set months ago and forgotten.

---

## Secrets

**No API key ships in the APK.** There is deliberately no `CLOUD_AI_API_KEY`: a key in an APK is
extractable with `unzip` and `strings` by anyone who downloads it, and would bill to the operator's
account. `CLOUD_AI_BASE_URL` points at a backend the operator controls, which holds the key and
authorises requests itself.

Build-time values come from `secrets.properties` (git-ignored) or environment variables, never from
a committed file. `.gitignore` excludes `*.jks`, `*.keystore`, `keystore.properties`,
`secrets.properties` and `google-services.json`.

**Banking credentials are never requested, entered or stored.** The app has no bank connection.
An account carries a free-text institution name and, optionally, the last four digits for
recognition — never a full account number, never a card number, never a password, never an OTP.

---

## Logging

`KhaataLog` is the only logging entry point in the app. Nothing calls `android.util.Log` directly.

- `redact()` masks anything that looks like an account or card number.
- **No amount, merchant, balance, account identifier or SMS body is ever logged**, at any level.
  The SMS importer logs an outcome and a confidence score; that is all.
- Release builds strip `Log.v`, `Log.d` and `Log.i` entirely via ProGuard's
  `assumenosideeffects` — a debug log left in cannot leak in production because the call is gone
  from the bytecode.

---

## Data at rest

The Room database is in the app's private directory, unreadable by other apps on a non-rooted
device.

**It is not encrypted.** SQLCipher was considered and left out of v1 deliberately: it costs a
native dependency, a noticeable slowdown on low-end devices, and — most importantly — a key that
has to live somewhere on the same device. Against threat 2 (another app), file permissions already
suffice. Against threat 3 (a stolen device), full-disk encryption, which every supported Android
version has on by default, is what actually protects the file. SQLCipher would add a real cost for
a marginal gain. See [V2.md](V2.md) for when that calculus changes.

## Data in transit

Everything financial stays on the device, so there is very little in transit. What there is —
ad requests, Play Billing, optional cloud AI — is HTTPS, and cleartext traffic is disabled
(`usesCleartextTraffic="false"`).

## Exported files

Backups and CSVs are written to `files/exports/` and shared through a `FileProvider` granting
temporary read access to **that one file**. `res/xml/file_paths.xml` exposes only the exports
directory — the database and receipt images are deliberately absent, so no share sheet can ever
hand out the ledger itself.

Exports are cleared when a new one is made, and can be cleared on demand. An export is a full copy
of the ledger sitting in app storage; keeping them indefinitely doubles the footprint of the thing
this app is most careful about.

CSV export guards against **formula injection**: a field beginning with `=`, `+`, `-` or `@` is
prefixed with a single quote. Without it, a merchant name a user typed becomes code executing on
whoever opens the file in Excel or Sheets.

---

## Components

- `SmsTransactionReceiver` is `exported="true"` (the system delivers to it) but requires
  `android:permission="android.permission.BROADCAST_SMS"`, so only the OS can invoke it — and it is
  `enabled="false"` until the user opts in.
- Every other receiver is `exported="false"`.
- `MainActivity` is the only exported activity.
- All `PendingIntent`s are `FLAG_IMMUTABLE`.

## What has not been verified

Stated because the spec asked for it plainly: **none of this has been penetration-tested, and no
security review has been run against a built APK.** The reasoning above is design intent supported
by code reading, not an audit finding. `./gradlew lint` has not been run.

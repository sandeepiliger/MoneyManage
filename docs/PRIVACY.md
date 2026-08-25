# Privacy

This document describes what the code actually does. The in-app privacy dashboard
(**Settings → Privacy**) says the same things to the user, and generates its "what can leave your
device" section from the live settings rather than from fixed prose — so it is correct for the
person reading it rather than for a hypothetical one.

## There is no account

No sign-up, no login, no user id, no server holding anyone's data. There is nothing to breach
because there is nothing held. This is the design decision every other one here follows from.

---

## What is stored on the device

Accounts, transactions, categories, budgets, goals, recurring rules, subscriptions, cards, loans,
investments, tags, merchant rules and settings — in the app's private storage, which other apps
cannot read.

`android:allowBackup="false"` in the manifest: the ledger is deliberately **excluded from Android's
automatic cloud backup**, because that would upload someone's complete financial history to Google
Drive without them ever asking. Backup is instead a file the user creates and places themselves —
see [BACKUP behaviour](#the-users-controls). `res/xml/backup_rules.xml` and
`res/xml/data_extraction_rules.xml` cover device-to-device transfer for the same reason.

## What is processed on the device

All of it. Every calculation, every insight, category suggestions, natural-language entry, bank-SMS
parsing, budget projections, EMI schedules, reports. `:core` has no network dependency of any kind
— it is a plain Kotlin library that could not make a request if it tried.

**The app works fully offline.** There is no degraded mode.

---

## What can leave the device

Nothing, by default. Each of the following is off unless the user turns it on, or is inherent to a
choice they made.

| What | When | Contains |
| --- | --- | --- |
| Ad requests | Free plan only, on two screens | No financial data. Google's SDK collects what AdMob collects — see [ADMOB.md](ADMOB.md) |
| Purchase verification | Only if the user subscribes | Google Play handles it; the app sees a product id and a token |
| Crash reports | **Off by default**, opt-in | Stack traces. No financial data — `KhaataLog.redact()` and the release ProGuard rules strip it |
| Usage analytics | **Off by default**, opt-in | Feature counts only. See the hard list below |
| AI questions | **Off by default**, opt-in, AI Pro only | The question and the figures needed to answer it |
| A backup file | Only when the user creates and shares one | Everything — it is their data, going where they send it |

### Analytics: what is never sent

Enforced by the type system, not by convention. `AnalyticsEvent` is a **sealed class with a closed
set of events**, each declaring its own parameters. There is no `track(name, map)` that could carry
an arbitrary payload.

Never sent, under any event:

- Transaction amounts
- Merchant names
- Bank or account information, including masked digits
- SMS content, in whole or in part
- Balances, net worth, or any figure derived from them
- Category names the user created
- Notes, tags, or any free text

What *is* sent, when the user has opted in: that a transaction was created and by which entry
method; that a budget was created and whether it was category-scoped; that the paywall was viewed;
a backup's **record count** (not its contents); an import's imported and rejected **counts**.

`ConsentGatedAnalyticsProvider` wraps the real provider and drops every event when consent is off,
so an event added later cannot bypass the check by forgetting to ask.

### Bank SMS

- **Off by default.** The receiver is declared `android:enabled="false"` in the manifest.
- Turning it on enables the component; turning it off **disables the component again**, so a user
  who has it off is not handed their messages at all. A stored boolean checked at delivery time
  would still mean the app receives every SMS on the phone; that is a different promise and a
  weaker one.
- Parsing is `BankSmsParser` — pure Kotlin in `:core`, no network access.
- **The message body is never stored.** `SmsTransactionImporter` deliberately passes `note = null`;
  putting the SMS in a note would carry bank text into exports, backups, and anything a future
  feature reads from a transaction.
- Logging records the outcome and the confidence score. Never the body, the merchant or the amount.
- Every import lands as `isPending` for the user to confirm.

### Cloud AI

- **Off by default**, and additionally gated on the AI Pro entitlement and on
  `CLOUD_AI_BASE_URL` being configured. All three must hold. The local engine handles everything on
  every plan.
- When on, the request carries the question and the figures needed to answer it — not the ledger.
- **No API key is embedded in the app.** `CLOUD_AI_BASE_URL` points at a backend the operator
  controls. See [AI_PROVIDER.md](AI_PROVIDER.md).

---

## Permissions

| Permission | Why | Optional |
| --- | --- | --- |
| `RECEIVE_SMS`, `READ_SMS` | Reading bank transaction messages | **Yes** — never requested unless the user turns SMS import on |
| `POST_NOTIFICATIONS` | Bill and budget reminders | Yes |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Ads, billing, optional cloud AI | Present, but nothing financial travels over it |
| `RECEIVE_BOOT_COMPLETED` | Re-scheduling reminders after a reboot | Yes |
| `SCHEDULE_EXACT_ALARM` | Bill reminders landing on the right day | Yes |
| `AD_ID` | Required by the AdMob SDK on the free plan | Yes — remove it if you ship without ads |
| `USE_BIOMETRIC` | App lock | Yes |

There is **no** storage permission. Backups are written to the app's own directory and shared
through a `FileProvider` URI granting read access to that one file; imports come through the system
document picker. The app never asks for access to the filesystem.

---

## The user's controls

All in **Settings → Privacy**:

- Turn analytics, crash reports, cloud AI and SMS reading on or off, each independently.
- **Export everything** — a JSON backup with every record, or a CSV of the ledger.
- **Delete everything** — `SettingsViewModel.confirmDeleteAll` removes every table, the profile,
  the stored PIN and every setting, then re-seeds categories so the app is usable rather than
  bricked. Partial deletion would be worse than none: a user who asked for their data to be gone
  and finds half still there has been misled.
- **Forget what Khaata has learned** — clears learned merchant rules while keeping the shipped set.
  Everything the app inferred about this user is inspectable in **Settings → Learned merchants** and
  individually removable. Automatic categorisation is only acceptable if it can be shown and
  corrected.

---

## Play Data Safety

What to declare, given the above. See [PLAY_STORE.md](PLAY_STORE.md) for the full form.

- **Data collected: none** — in the default configuration.
- **Data shared: none.**
- With analytics or crash reporting enabled: *App activity → App interactions* and
  *App info and performance → Crash logs*, both marked **optional** and **not linked to identity**.
- Ads: declare per the AdMob SDK's own disclosures.
- **Financial info: not collected.** This is accurate. It never leaves the device.

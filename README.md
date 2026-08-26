# Khaata

An India-first personal finance app for Android. Kotlin, Jetpack Compose, Room, offline-first.

Khaata tracks where money actually goes, in a couple of taps, without sending anyone's financial
data anywhere. Everything — every calculation, every insight, category suggestions, natural-language
entry, bank-SMS parsing — happens on the device.

---

## Status of this build

Read this before anything else in this file.

| Area | State |
| --- | --- |
| `:core` (money, calculations, parsing, insights, backup, entitlements) | **Compiles and tests pass**, on every CI run |
| `:app` (Room, repositories, Compose UI, DI, workers, billing, ads) | **Compiles** — debug and R8-minified release, on every CI run, against API 36 |
| Ever run on a device or emulator | **No.** Not once. This is the largest remaining unknown |
| Android lint | Configured with `abortOnError`, but no baseline is committed and CI bootstraps one with `-Dlint.baselines.continue=true` — so it has never actually gated a build |
| Instrumentation tests and `:app` unit tests | Written, never executed — CI runs neither |
| Play Store readiness | Not verified; see [KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) |

The Android toolchain cannot be installed in the environment this is developed in: `dl.google.com`
is blocked by an egress policy, so AGP, the Android SDK and the AndroidX artifacts are unreachable
there. The project is therefore split so that the half where a bug is most expensive — money
arithmetic, budget pacing, EMI amortisation, credit-card cycles, recurrence, SMS and
natural-language parsing, insights, backup, entitlements — is a **pure-JVM module that compiles
locally and whose tests genuinely run and can fail.** That testing caught eleven real bugs during
development.

The Android half is compiled by CI on every push — debug and R8-minified release, against API 36.
What it has never been is **run**: no screen in this app has ever rendered on a device or an
emulator. Nothing here should be read as a claim that it has.

---

## What it does

**Core loop** — record an expense in two or three taps: a custom keypad that is on screen
immediately, a category suggested from the merchant, and save. No sign-up, no account, no server.

**Indian by default, not by translation.** Amounts group in lakh and crore (`₹1,40,000`, `₹1.4L`).
The financial year runs April to March. The month can start on the 7th if that is when the salary
lands. Categories are the ones Indian households actually discuss — auto, FASTag, cooking gas,
society maintenance, household help, parents, festivals — and about 180 Indian merchants are
recognised out of the box. Bank SMS is parsed for UPI, NEFT, IMPS, RTGS, ATM, POS and EMI.

**Budgets** that pace rather than scold: a projection of where the month ends up at the current
rate, and a safe-daily-spend figure, so "over budget" arrives as a warning rather than as news.

**Recurring, subscriptions, credit cards, loans, investments, goals.** EMI amortisation, statement
cycles and due dates, portfolio returns with staleness warnings, goal pacing.

**Reports** over seven periods including the financial year, every chart paired with the same
figures as text.

**An assistant** that answers questions about your own data using a local rule-based engine.
A cloud model is an option behind an explicit opt-in, never a default, and never required.

---

## Non-negotiables in the design

These are enforced in code and tested where they can be tested.

**Money is never a `Double`.** Every amount is a `Money` — a `BigDecimal` carrying a currency,
scale-checked at construction, stored as minor-unit `Long`. Mixed-currency arithmetic throws rather
than silently producing nonsense.

**Balances are derived, never stored.** The ledger is the single source of truth. An account's
balance, a budget's progress and a card's outstanding amount are computed from transactions. There
is no second copy to drift.

**Transfers are excluded from income, expenses and budgets — everywhere.** Someone who moved
₹50,000 into a fixed deposit has not spent ₹50,000. `CommitmentCalculator`, `CashflowAnalyzer` and
`BudgetCalculator` each have a test that fails if this stops being true.

**Nothing is auto-posted to the ledger.** Recurring rules default to reminding rather than writing.
Every SMS import lands as pending, for the user to confirm. The app knows rent was *due*; it does
not know it went out.

**Nothing leaves the device by default.** No account, no sign-in, no sync. See
[PRIVACY.md](docs/PRIVACY.md) for exactly what can leave and under which switch.

**Colour never carries meaning alone.** Every state is stated in words as well: "over budget",
"high utilisation", "hidden", "paused". Income is teal and expense is rose rather than green and
red — red–green colour blindness is the common one, and framing every purchase in alarm-red is a
design choice about how the app talks to people.

---

## Documentation

| Document | What is in it |
| --- | --- |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Module layout, layering, why `:core` is separate |
| [FEATURES.md](docs/FEATURES.md) | Every feature, and the design decision behind it |
| [SETUP.md](docs/SETUP.md) | Building it, environment variables, secrets |
| [SCHEMA.md](docs/SCHEMA.md) | Room entities, indexes, migration policy |
| [TESTING.md](docs/TESTING.md) | What is tested, what passes, what has not been run |
| [PRIVACY.md](docs/PRIVACY.md) | What is stored, processed and sent — and what never is |
| [SECURITY.md](docs/SECURITY.md) | App lock, PIN hashing, what is never stored |
| [PLAY_STORE.md](docs/PLAY_STORE.md) | Data safety form, permissions, release checklist |
| [BILLING.md](docs/BILLING.md) | Products, tiers, Play Console setup |
| [ADMOB.md](docs/ADMOB.md) | Ad placements and the rules they follow |
| [AI_PROVIDER.md](docs/AI_PROVIDER.md) | The local engine and how to add a cloud one |
| [KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) | What is unfinished, unverified or deliberately absent |
| [V2.md](docs/V2.md) | What to build next, and what to leave alone |

---

## Quick start

```bash
# The JVM half — this works anywhere with a JDK 17+. The -D flag keeps the root
# build.gradle.kts plugins block from touching Google's Maven (AGP lives there only).
./gradlew -Pkhaata.androidModule=false -Dkhaata.androidModule=false :core:test

# The whole thing, once an Android SDK is available.
./gradlew assembleDebug
```

No credentials are needed to build or run. Ads fall back to Google's published test unit IDs,
billing degrades to an unavailable state, and cloud AI stays off. See [SETUP.md](docs/SETUP.md).

---

## Licence and attribution

Khaata is an original application. It was designed with reference to what Money Manager
(Realbyte), Wallet (BudgetBakers) and Monefy do well and badly, but no screenshot, layout, colour,
icon, string, asset or interaction flow is taken from any of them. Third-party library attribution
is in the app under **Settings → About → Open-source licences**, and in
`ThirdPartyNotices.kt`.

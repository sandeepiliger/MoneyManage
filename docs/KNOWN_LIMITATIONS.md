# Known limitations

Everything here is a real gap, stated plainly rather than left to be discovered.

## The big one

**The `:app` module has never been compiled.**

`dl.google.com` is blocked by the egress policy of the environment this was built in, so the
Android Gradle Plugin, the Android SDK and every AndroidX artifact were unreachable. I confirmed
this with `curl` and with `$HTTPS_PROXY/__agentproxy/status`, and did not route around it.

What follows from that:

- `:app` has **not** been type-checked. Expect import errors, signature mismatches and Compose API
  drift on the first real build. The code is written carefully and cross-referenced against the
  actual declarations in this repository — I checked signatures as I went, and fixed several
  mismatches that way — but "carefully written" is not "compiles".
- Android **lint has not run**. `abortOnError = true`, so it must pass before a release.
- **No screen has ever rendered.** Layouts, spacing and theming are unverified by eye.
- The app-module tests are written but have **never executed**.

What *was* verified: `:core` compiles and **369 tests pass**, covering money arithmetic, budgets,
loans, cards, recurrence, parsing, insights, backup and entitlements. That is the half where a bug
is most expensive, and it is genuinely tested — see [TESTING.md](TESTING.md).

---

## Unimplemented

| Gap | Impact |
| --- | --- |
| **No Compose UI tests** | Semantics are written for them; nothing asserts a screen renders or that a tap works. |
| **No UMP consent flow** | Blocks an EEA/UK release with ads. Not required for India. |
| **Receipt attachments** | `receipts` table and `RECEIPT_ATTACHMENTS` entitlement exist; there is no camera or file-picker UI behind them. |
| **Scheduled backups** | The `SCHEDULED_BACKUP` entitlement exists; no worker performs one. Backup is manual only. |
| **Family sharing** | The FAMILY tier's three features are named in `Feature` and priced on the paywall, but **nothing implements them.** Do not sell this tier until they exist. |
| **Notification-based import** | `notificationImportEnabled` exists in settings; no `NotificationListenerService` is implemented. |
| **Dashboard reordering** | Card order is stored and read; there is no drag-to-reorder UI. |
| **Multi-currency** | `Money` is currency-typed and mixed arithmetic throws, but there are no exchange rates, so an account in a second currency cannot be summed into net worth. Single-currency in practice. |
| **Custom date ranges in reports** | `CUSTOM_DATE_RANGES` is a Pro feature; the UI offers seven fixed periods and no picker. |

The FAMILY tier is the one to act on before shipping: a paywall that takes money for features that
do not exist is not a limitation, it is a refund.

## Partially done

- **Migration testing** — no migration exists yet, so there is nothing to test. The pattern is in
  [SCHEMA.md](SCHEMA.md) and `MigrationExample` is kept in the source as a worked reference.
- **CSV import** matches accounts and categories by name and rejects rows whose account does not
  exist. There is no mapping UI to resolve them instead — a rejected row is reported, not fixable
  in-app.
- **Hindi** covers 517 of 717 strings (72%). Missing keys fall back to English, which is
  correct behaviour but means some screens are mixed.

## Not verified

- **No penetration test, no security audit.** [SECURITY.md](SECURITY.md) is design intent supported
  by code reading, not audit findings.
- **No accessibility audit.** TalkBack has not been run. Content descriptions, touch targets and
  spoken money formats are written for it and unverified.
- **No performance measurement.** No benchmark, no baseline profile, no measurement of the
  two-to-three-second entry claim. The SQL aggregates exist because folding a large ledger in
  Kotlin *should* be slow on a mid-range phone; nobody has measured either.
- **Play Billing has never connected.** Written against the v7 API, never run.
- **AdMob has never rendered.** `AdSlot` is untested.
- **SMS parsing is tested against synthetic messages** written from documented Indian bank formats.
  It has not been run against a real inbox, and banks change their formats without notice. Expect
  to iterate. This is also why every import lands as pending: the design assumes the parser will
  sometimes be wrong.

## Deliberately not done

Distinct from the above — these are decisions, not gaps.

- **No database encryption.** SQLCipher costs a native dependency, a slowdown on low-end devices,
  and a key that lives on the same device. Against another app, file permissions already suffice;
  against a stolen device, full-disk encryption is what protects the file. Reasoning in
  [SECURITY.md](SECURITY.md).
- **No cloud sync.** Not a missing feature — the absence of a server is the product. Backup is a
  file the user places themselves.
- **No wipe-after-N-failed-PINs.** A child mashing the keypad should not destroy someone's
  financial history, and an attacker can copy the storage before guessing anyway.
- **No auto-posting of recurring transactions by default.** The app knows rent was *due*; it does
  not know it went out.
- **Two category levels only.** Deeper nesting reads as flexibility until you are three taps into a
  picker at a shop counter.
- **No `track(name, params)` analytics call.** The event set is sealed, so an arbitrary payload
  cannot be sent even by accident.

## If you are picking this up

In order:

1. Get `:app` compiling. Budget real time for it.
2. Run lint, fix what it finds.
3. Run the instrumentation tests — particularly `TransactionAggregateParityTest`, which checks the
   thing most expensive to get wrong.
4. Run the app. Look at every screen. Nobody has.
5. Add Compose UI tests for the transaction-entry flow first; it is the one people use daily.
6. Either implement the FAMILY tier or remove it from the paywall.

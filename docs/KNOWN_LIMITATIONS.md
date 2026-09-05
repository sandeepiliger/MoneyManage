# Known limitations

Everything here is a real gap, stated plainly rather than left to be discovered.

## The big one

**No screen has ever rendered.**

The app now **compiles**. A GitHub Actions workflow (`.github/workflows/android-build.yml`)
builds `:app:assembleDebug` and the R8-minified `:app:assembleRelease` on every push, against
API 36, and both succeed. The development sandbox still has `dl.google.com` blocked by egress
policy, so `:app` cannot be compiled there — CI is the only compiler, and it is the gate that
matters.

What that leaves:

- **Nobody has looked at this app.** Layouts, spacing, theming and every interaction are
  unverified by eye. Compiling is not running.
- Android **lint has never actually gated a build**. `abortOnError = true` and a `lint-baseline.xml`
  is configured, but no baseline is committed; CI passes `-Dlint.baselines.continue=true`, which
  generates one and continues instead of failing. Someone must commit a real baseline, or run lint
  and fix what it finds, before the first release.
- The **instrumentation** tests are written but have **never executed** — CI has no emulator, so
  `connectedAndroidTest` has never run. The app-module *unit* tests now do run on every push
  (`:app:testDebugUnitTest`); they were in the same never-executed state until then.
- Play Billing has never connected and AdMob has never rendered; both need an internal-testing
  track run.

What *is* verified: `:core` compiles and its tests pass on every CI run, covering money
arithmetic, budgets, loans, cards, recurrence, parsing, insights, backup and entitlements, and
the `:app` unit tests covering the restore state machine run alongside them. That is
the half where a bug is most expensive — see [TESTING.md](TESTING.md).

---

## Unimplemented

| Gap | Impact |
| --- | --- |
| **No Compose UI tests** | Semantics are written for them; nothing asserts a screen renders or that a tap works. |
| **No UMP consent flow** | Blocks an EEA/UK release with ads. Not required for India. |
| **Receipt attachments** | `receipts` table and `RECEIPT_ATTACHMENTS` entitlement exist; there is no camera or file-picker UI behind them. |
| **Scheduled backups** | The `SCHEDULED_BACKUP` entitlement exists; no worker performs one. Backup is manual only. |
| **Family sharing** | The FAMILY tier's three features are named in `Feature` but **nothing implements them.** They are listed in `Feature.UNSHIPPED`, so `isUnlocked` refuses them and `PaywallViewModel` drops any tier whose every feature is unshipped — the tier does not appear on the paywall and cannot be bought. Sharing a household ledger needs a server this app deliberately does not have, so this is not close. |
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
- **Hindi is complete** — all 791 string resources are translated, with matching format
  specifiers (verified by name-diff against `values/strings.xml`). It has not been reviewed by a native speaker in the running app, so
  register and truncation on real screens are unverified.

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

1. **Run the app. Look at every screen.** Nobody has. This is the single largest unknown left.
2. Commit a real lint baseline (or run lint and fix what it finds) so `abortOnError` actually
   gates something.
3. Run the instrumentation tests — particularly `TransactionAggregateParityTest`, which checks the
   thing most expensive to get wrong. These still need an emulator; the unit tests already run in
   CI.
4. Add Compose UI tests for the transaction-entry flow first; it is the one people use daily.
5. Before any Play upload: real values in `secrets.properties` (the build now refuses to produce a
   release artifact carrying placeholders), an upload keystore, the SMS Permissions Declaration,
   and the Financial features declaration.

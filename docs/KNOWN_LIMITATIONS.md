# Known limitations

Everything here is a real gap, stated plainly rather than left to be discovered.

## The big one

**Nothing is tested on a device automatically.**

A GitHub Actions workflow (`.github/workflows/android-build.yml`) builds `:app:assembleDebug` and
the R8-minified `:app:assembleRelease` on every push, against API 36, runs the `:core` and `:app`
unit tests, and publishes both APKs to the rolling `latest-build` release. The debug build has been
installed and used on a real phone by the owner, so screens do render — but only by hand. The
development sandbox has `dl.google.com` blocked, so `:app` compiles only in CI.

What that leaves:

- **No systematic look at every screen.** Layouts, spacing, dark mode and every interaction have
  been checked only where someone happened to use them.
- Android **lint has never actually gated a build**. `abortOnError = true` and a `lint-baseline.xml`
  is configured, but no baseline is committed; CI passes `-Dlint.baselines.continue=true`, which
  generates one and continues instead of failing. Someone must commit a real baseline, or run lint
  and fix what it finds, before the first release.
- The **instrumentation** tests are written but have **never executed** — CI has no emulator, so
  `connectedAndroidTest` has never run. `TransactionAggregateParityTest`, which checks every SQL
  total (balances, net worth, month, category, daily and filtered totals) against the Kotlin rules,
  was moved out of them and now runs in CI under Robolectric's real SQLite, including a seeded
  3,000-row random ledger.
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
| **Family sharing** | The FAMILY tier's three features are named in `Feature` but **nothing implements them.** They are listed in `Feature.UNSHIPPED`, so `isUnlocked` refuses them and `PaywallViewModel` drops any tier whose every feature is unshipped — the tier does not appear on the paywall and cannot be bought. Sharing a household ledger needs a server this app deliberately does not have, so this is not close. |
| **Notification-based import** | `notificationImportEnabled` exists in settings and nothing reads it; no `NotificationListenerService` is implemented and no screen offers it. Needs a Play policy declaration as well as code. |
| **AI insights and categorisation** | `AI_ENHANCED_INSIGHTS` and `AI_SMART_CATEGORISATION` are AI Pro features with no implementation; they are in `Feature.UNSHIPPED`, so they are neither shown nor sold. The cloud **assistant** is implemented (`CloudFinancialAiService`) and is offered once `CLOUD_AI_ENDPOINT` is configured; see [AI_PROVIDER.md](AI_PROVIDER.md). |
| **Transaction tags** | `Transaction.tags` is stored, backed up, exported and filterable, but no screen lets anyone add a tag. |
| **Multi-currency** | `Money` is currency-typed and mixed arithmetic throws, but there are no exchange rates, so an account in a second currency cannot be summed into net worth. Single-currency in practice. |

Goals creation, scheduled backups, dashboard customisation and custom report ranges were all in
this table and are now built; their entitlement flags have left `Feature.UNSHIPPED` accordingly.
What remains is either a testing gap or needs something this repository cannot supply on its own —
a server, a configured cloud endpoint, or a Play policy declaration.

The FAMILY tier is the one to act on before shipping: a paywall that takes money for features that
do not exist is not a limitation, it is a refund. It is withheld today by `Feature.UNSHIPPED`,
which is the right holding position and not a substitute for deciding whether to build or drop it.

## Partially done

- **Migration testing** — the first migration exists (v1 → v2: `accounts.openingBalanceDate`, a
  single nullable `ALTER TABLE ... ADD COLUMN`). It has no `MigrationTestHelper` test because
  `app/schemas/` has never been committed: Room exports the schema JSON at build time, and the only
  builds that run are in CI, which does not commit its output. Room still validates the migrated
  table against the entity when the database opens and refuses to open on a mismatch rather than
  corrupting anything. Commit the generated `app/schemas/` from a local build before the next
  entity change.
- **CSV import** matches accounts and categories by name and rejects rows whose account does not
  exist. There is no mapping UI to resolve them instead — a rejected row is reported, not fixable
  in-app.
- **Hindi is complete** — every string resource is translated, with matching format
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
- **SMS transfer pairing is a heuristic.** A debit and a credit of the same amount on two of the
  user's accounts within two days, both still pending and without conflicting references, become
  one transfer. Two unrelated payments that happen to match are merged too: balances stay right,
  but that spend and that income drop out of the reports. Card bill payments become a transfer
  only when exactly one credit card account exists.
- **Opening balances are dated by day.** Transactions dated before an account's opening-balance
  date never move it. A transaction on that same day that happened before the balance was entered
  still counts once confirmed, unless it came from an inbox scan, which can tell by the message's
  timestamp and skips it.

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

1. **Walk every screen on a real device**, light and dark, English and Hindi. Only the paths the
   owner has used have been looked at.
2. Commit a real lint baseline (or run lint and fix what it finds) so `abortOnError` actually
   gates something.
3. Run the remaining instrumentation tests on an emulator. The most important one,
   `TransactionAggregateParityTest`, already runs in CI.
4. Add Compose UI tests for the transaction-entry flow first; it is the one people use daily.
5. Before any Play upload: real values in `secrets.properties` (the build now refuses to produce a
   release artifact carrying placeholders), an upload keystore, the SMS Permissions Declaration,
   and the Financial features declaration.

# Play Store

## Before you can ship

**This build has not been compiled, linted, run, or tested on a device.** Everything below is what
release preparation requires; none of it has been done. See
[KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

Blockers, in order:

1. Build `:app` against a real Android SDK and fix whatever falls out.
2. `./gradlew lint` — `abortOnError = true` is set, so it must pass.
3. Run the instrumentation tests on a device.
4. Replace the placeholder URLs and support email (they point at `example.invalid`).
5. Write and host a real privacy policy and terms.
6. Replace the AdMob test IDs, or remove ads.
7. Create the four subscription products in Play Console.
8. Generate a signing key and configure `keystore.properties`.
9. Take screenshots and write the listing.

---

## Data Safety form

Derived from [PRIVACY.md](PRIVACY.md). In the default configuration:

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | **No** |
| Is all user data encrypted in transit? | Yes — `usesCleartextTraffic="false"` |
| Do you provide a way to delete data? | Yes — Settings → Delete all my data |

**Financial info: not collected.** This is accurate and worth stating clearly: transactions,
balances and account details never leave the device.

If you enable analytics or crash reporting, declare:

| Type | Purpose | Linked to identity | Optional |
| --- | --- | --- | --- |
| App activity → App interactions | Analytics | No | **Yes** |
| App info and performance → Crash logs | Diagnostics | No | **Yes** |

Both are off by default, so "optional" is truthful.

If you ship ads, add the AdMob SDK's declared collection (device identifiers, approximate location
for ad targeting). Google publishes the disclosure text; use theirs rather than paraphrasing.

---

## Sensitive permissions

`READ_SMS` and `RECEIVE_SMS` are **restricted permissions**. Play requires a declaration form and
approval, and the bar is high — most apps requesting SMS access are rejected.

### The declaration

- **Core functionality:** automatically capturing bank transaction messages so a user does not have
  to type each transaction manually. In India, transaction SMS is the primary notification channel
  for UPI, card and account activity; manual entry is the friction this feature exists to remove.
- **User benefit:** entry drops from typing four fields to confirming a parsed row.
- **Why no alternative works:** the SMS Retriever API only returns messages the app itself
  triggered. Notification listening covers only the banks that post notifications, needs a
  permission users find more alarming, and misses anything received while the phone is off.

### What supports the case

- The permission is **never requested unless the user turns SMS import on**.
- The receiver is `android:enabled="false"` in the manifest and is enabled and disabled with the
  setting, so a user who declines has no SMS receiver registered at all.
- Messages are parsed **on the device**; the body is never stored, logged, exported or transmitted.
- Every import lands as pending for the user to confirm.
- The app is fully functional without it.

Record a screen capture of the opt-in flow, including the explanation screen, for the review.

**If SMS approval is refused**, remove the two permissions and the receiver from the manifest.
Everything else works; the app loses one convenience. Weigh this before your first submission —
you may prefer to ship without SMS and add it in an update once the app has a track record.

---

## Listing

**Title:** Khaata — Expense Tracker (under 30 characters)

**Short description** (80 characters): *Track spending in seconds. Budgets, bills, UPI. Your data
stays on your phone.*

**Full description** should lead with what makes it different, not with a feature list:

- Everything stays on the device — no account, no sign-up, no server.
- Built for India: lakh and crore, the April–March financial year, UPI and bank SMS, categories
  people here actually use.
- Two or three seconds to record an expense.
- The core is free forever; Pro adds extras.

Avoid: "bank-grade security" (meaningless), "AI-powered" as a headline (the local engine is rules,
and saying otherwise is a claim you cannot support), and any implication of financial advice.

**Category:** Finance. **Content rating:** Everyone.

Screenshots: dashboard, transaction entry (the keypad), budgets, reports, privacy dashboard. Use
demo data — it exists for this — and never a real ledger.

---

## Release checklist

- [ ] `./gradlew :app:assembleRelease` succeeds
- [ ] `./gradlew lint` passes with `abortOnError = true`
- [ ] `./gradlew :core:test` passes
- [ ] `./gradlew :app:testDebugUnitTest` passes
- [ ] `./gradlew :app:connectedDebugAndroidTest` passes on a real device
- [ ] Placeholder URLs and support email replaced
- [ ] Privacy policy and terms live at those URLs
- [ ] AdMob production IDs set, or ads removed with the `AD_ID` permission
- [ ] Subscription products created and priced
- [ ] Signing key generated, backed up somewhere you will still have it in three years
- [ ] Play App Signing enrolled
- [ ] R8 mapping file uploaded with the bundle
- [ ] Tested on a low-end device — the target user's phone is not a flagship
- [ ] Tested in Hindi
- [ ] TalkBack pass over the main flows
- [ ] Backup exported and restored on a second device
- [ ] Data Safety form completed
- [ ] SMS permission declaration submitted, or the permission removed

## Do not claim

The spec was explicit about this and it is worth repeating on a page about a store listing: **do
not describe this build as production-certified, security-audited, or performance-tested.** None of
those has happened. Ship it as what it is.

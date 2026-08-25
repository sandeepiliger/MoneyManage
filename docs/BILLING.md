# Billing

Google Play Billing Library 7.1.1, behind `BillingProvider`.

## Tiers

| Tier | Product id | Indicative price | Adds |
| --- | --- | --- | --- |
| FREE | — | — | Everything below |
| PRO | `khaata_pro_yearly` | ₹199/yr | No ads, unlimited accounts, advanced reports, custom date ranges, receipt attachments, scheduled backups, budget rollover, dashboard customisation |
| AI_PRO | `khaata_ai_pro_yearly` | ₹499/yr | Everything in Pro, plus the cloud assistant, AI-enhanced insights, smarter categorisation |
| FAMILY | `khaata_family_yearly` | ₹799/yr | Everything above, plus shared household finances, family budgets, shared goals |

Prices here are indicative for planning. **The app never displays a price it computed itself** —
every price shown comes from `ProductDetails.formattedPrice`, the store's own localised string. A
hardcoded "₹199" is wrong the moment there is a regional price, a sale, or a currency change.

## What is always free

Defined in `Feature`, where `minimumTier` is the single source of truth:

```
UNLIMITED_TRANSACTIONS   BASIC_REPORTS      BUDGETS
GOALS                    RULE_BASED_INSIGHTS  CSV_EXPORT
JSON_BACKUP              NATURAL_LANGUAGE_ENTRY  BIOMETRIC_LOCK
```

Recording spending, seeing where it went, budgeting, and **exporting your own data** are free
forever. An expense tracker that will not let you track expenses has no users to convert, and an
app that holds your data hostage behind a subscription deserves the review it gets.

Free accounts are capped by **count** (four) rather than by feature, so someone with one bank
account and a wallet is never blocked from the thing they installed the app for.

## The paywall derives itself

`PaywallViewModel.featuresIntroducedBy(tier)` filters `Feature.entries` on `minimumTier == tier`.
Moving a feature between tiers updates the paywall automatically, so the screen can never advertise
something the entitlement check will then refuse.

No UI anywhere compares tiers directly. Everything asks `EntitlementRepository.isUnlocked(feature)`.

---

## Play Console setup

1. **Monetise → Subscriptions → Create subscription** for each product id above.
2. One base plan per subscription, **auto-renewing, yearly**.
3. Set India (INR) as the home price; let Play convert for other regions.
4. An optional free trial goes on the base plan as an offer. The app reads `freeTrialPeriod` and
   renders it via `IsoPeriod.days` — `P7D`, `P1W` and `P1M` all work.
5. Add licence testers under **Setup → License testing** so purchases in testing are free and
   renew on the accelerated schedule.
6. Upload a build to **internal testing** — billing does not work in a sideloaded debug build.

## Handling pending purchases

UPI mandates in India frequently take **hours** to clear. A pending purchase is treated as a
first-class state, not an error:

- `Entitlement.isPending` is set, and nothing is unlocked yet — the money has not moved.
- The paywall shows an explicit "your payment is being processed" card.
- `PaywallViewModel` observes `billingProvider.purchases`, so a mandate clearing while the app is
  open unlocks immediately, without the user going back to the paywall.

Telling a user who has paid that their purchase failed is a support ticket and a one-star review.

## Acknowledgement

Play **refunds an unacknowledged purchase after three days.** `PaywallViewModel.restore()`
acknowledges anything the store still considers unacknowledged, which is what happens when a
purchase completes and the process is killed before it can confirm. This is not tidiness; it is the
difference between being paid and not.

## Entitlement rules

`EntitlementManager`:

- An **expired** subscription falls back to FREE.
- A subscription **in its grace period** keeps working — the user has paid and their card simply
  failed to renew.
- A **pending** purchase grants nothing.
- Downgrading never deletes data. A user who lets Pro lapse with six accounts keeps all six; they
  simply cannot add a seventh. Deleting accounts on downgrade would be destroying data the user
  paid to create.

## Degrading without Play

`BillingConnectionState.UNAVAILABLE` — no Play services, an unconfigured build, a device in a
region without Play — produces an empty product list. The paywall says purchases are not available
and **every free feature keeps working**. Nothing required to track money touches billing.

`NoOpBillingProvider` is the binding used when billing is compiled out entirely.

## Not verified

`PlayBillingProvider` is written against the v7 API and **has never been run against a real Play
connection.** Expect to iterate on the first real purchase flow.

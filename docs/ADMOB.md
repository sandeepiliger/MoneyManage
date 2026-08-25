# Ads

Google Mobile Ads, behind `AdProvider`. Two implementations exist — `NoOpAdProvider` and
`AdMobAdProvider` — and nothing outside `core/ads` imports an SDK type.

## Where ads may appear

The list is short, and **what is absent matters more than what is present**.

| Placement | Format | Cap |
| --- | --- | --- |
| `REPORTS_FOOTER` | Banner | Below the charts, after the content |
| `MORE_MENU_FOOTER` | Banner | Bottom of the More menu |
| `NAVIGATION_INTERSTITIAL` | Interstitial | ≥4 hours apart, ≤3/day, **not in the first 7 days**, and not before 20 transactions have been recorded |
| `REWARDED_UNLOCK` | Rewarded | Only when the user chooses to watch one |

## Where ads never appear

Enforced by `AdPlacement` being a closed enum — a screen cannot invent a placement, and `AdSlot`
rejects anything that is not a banner at the call site.

- **Not on the amount keypad.** Someone entering a number is mid-task.
- **Not after saving a transaction.** The moment a habit is being formed is not the moment to
  interrupt it. This is the single most common dark pattern in this category and it is the reason
  people uninstall.
- **Not on the transaction list.**
- **Not anywhere a balance, budget figure, or account number is on screen.** An ad next to
  someone's bank balance reads as an ad *about* their bank balance. That is a trade this app does
  not make.

## The interstitial grace period

`AdConfig.interstitialGraceDays = 7` and `interstitialMinimumTransactions = 20`. A new user is
still deciding whether to trust an app with their finances; interrupting them in week one to sell
an impression is a poor trade for an install.

Frequency state lives in `PersistedAdImpressionStore` — a last-seen timestamp per placement, a
per-day counter, and a first-use date, in the app's own key-value table. **It records counts and
timestamps, never what was shown or what the user did next.** It is not a profile. The day boundary
uses the device's timezone, so "three a day" means the user's day rather than resetting at 5:30am
in IST.

## Every banner is labelled

`AdSlot` renders a visible "Advertisement" label above the ad. An unlabelled banner inside a
finance app can be mistaken for the app's own recommendation, which is the one thing an ad here
must never look like.

When an ad is not allowed, `AdSlot` **renders nothing at all** — not a placeholder, not reserved
space. A paying user's layout is identical minus the ad, rather than having a gap where one used
to be.

## No ads for paying users

`AdProvider.adsEnabled` is driven by `EntitlementRepository.observeShouldShowAds()`. For an
entitled user the AdMob SDK is **never initialised** — `AdMobAdProvider.initialize()` returns
before calling `MobileAds.initialize`. Not just "no ads shown": no SDK, no requests, no ad id read.

## Setup

1. Create an AdMob app and three ad units (banner, interstitial, rewarded).
2. Put the ids in `secrets.properties` — see [SETUP.md](SETUP.md).
3. Without them, the build uses **Google's published test unit IDs**. Test ads render; there is no
   revenue and no policy risk.

**Never ship a release with the test IDs**, and never test with production IDs — clicking your own
production ad is what gets an AdMob account suspended.

## Consent

Ads in the EEA and UK need a CMP under Google's UMP requirements. **This is not implemented.** For
an India-first launch it is not immediately required, but it is a blocker for a European release.
See [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

The `AD_ID` permission is declared because the SDK requires it. If you ship without ads, remove it
from the manifest — an unused `AD_ID` declaration has to be disclosed on the Data Safety form for
no benefit.

## Turning ads off entirely

Change one Hilt binding:

```kotlin
@Provides @Singleton
fun provideAdProvider(noOp: NoOpAdProvider): AdProvider = noOp
```

Then remove the `play-services-ads` dependency, the `AD_ID` permission, and the `admobAppId`
manifest placeholder. Nothing else in the app references an ad type.

## Not verified

`AdSlot` has never rendered. The AdMob integration is written but has not been run.

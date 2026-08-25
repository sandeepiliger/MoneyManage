# Architecture

## Modules

```
:core   pure JVM   — money, domain models, calculations, parsing, insights,
                     backup format, entitlement rules, AI abstraction
:app    Android    — Room, repositories, Compose UI, DI, workers, billing, ads
```

`:core` has **no Android dependency at all** — not `androidx`, not `android.util.Log`, nothing.
It is a plain Kotlin library that a JVM can compile and test.

### Why the split

Two reasons, in this order.

**The correctness-critical half becomes verifiable without a device.** Money arithmetic, EMI
amortisation, budget pacing, statement cycles, recurrence, SMS parsing — these are where a bug is
most expensive and least visible. In `:core` they run under a plain JUnit invocation in a few
seconds. 369 tests do, on every change. During development this caught eleven real bugs, including
three unreachable merchant rules, an EMI schedule off by one instalment, and a natural-language
parser reading the digits of a date as an amount.

**It also happened to be the only half buildable in the environment this was written in**, where
`dl.google.com` is unreachable. That is a coincidence rather than the design rationale, but it is
a useful demonstration of the point: the module boundary is real enough that half the project
compiles and tests with no Android toolchain present.

The Gradle property `khaata.androidModule=false` drops `:app` from the build entirely, which is
what makes `./gradlew -Pkhaata.androidModule=false :core:test` work anywhere.

---

## Layering inside `:app`

```
feature/*        Compose screens + ViewModels        (UI state, no business rules)
   ↓
data/repository  repositories                        (orchestration, no arithmetic)
data/backup      BackupManager                       (file IO, restore ordering)
data/demo        DemoDataManager
   ↓
core/database    Room entities, DAOs, mappers
core/*           ads, billing, analytics, security, notifications, sms, ui
   ↓
:core            domain models + calculators         (all the arithmetic)
```

Dependencies point downward only. A calculator never sees a Room entity; a ViewModel never does
money arithmetic.

### Where each kind of logic lives

| Kind | Home | Why |
| --- | --- | --- |
| Money arithmetic, pacing, amortisation, cycles | `:core/calc` | Testable without a device |
| Parsing (SMS, natural language, CSV) | `:core/sms`, `/nlp`, `/backup` | Same |
| "What does this mean" rules (insights, entitlements) | `:core/insights`, `/entitlement` | Same |
| Persistence | `:app/core/database` | Needs Room |
| Orchestration across repositories | `:app/data` | Needs coroutines + DAOs |
| UI state, navigation, formatting for display | `:app/feature` | Needs Compose |

**Anything pure that starts life in a ViewModel gets moved down.** `CommitmentCalculator`,
`ReportPeriod` and `IsoPeriod` all began as private functions inside ViewModels and were moved to
`:core` precisely because no test in this environment could reach them there. Moving them also
removed three duplicate implementations.

---

## Key structural decisions

### Balances are derived, never stored

There is no `balance` column. `BalanceCalculator.balances()` folds the ledger; the SQL aggregates
in `TransactionDao` do the same thing in the database for speed. Both must agree, and
`TransactionAggregateParityTest` exists to make sure they do — a stored balance would be a second
source of truth, and the two would eventually disagree in a way the user cannot detect.

The same applies to budget progress, credit-card outstanding, portfolio value and goal progress.

### Money is a type, not a number

```kotlin
class Money private constructor(val amount: BigDecimal, val currency: CurrencyCode)
```

Scale is checked at construction. `plus` on two different currencies throws rather than producing a
number. Storage is minor-unit `Long` — SQLite has no decimal type, and a `REAL` column would
reintroduce floating point at the persistence layer after all the care taken above it.

`MoneyMath.PRECISION` (24 digits, HALF_EVEN) is the working precision for intermediates —
interest, returns, monthly normalisation — with rounding to a payable amount only at the end.

### Rules as data, not as materialised rows

Budgets, recurring rules and goals are stored as **rules**. Occurrences, periods and progress are
computed on demand. A recurring rule does not write twelve future transactions; it writes one when
an occurrence is actually confirmed. This is what makes editing a rule safe — there is no back
catalogue of derived rows to reconcile.

`RecurrenceCalculator` derives every occurrence from the anchor date rather than by stepping
forward from the last one, so a rule anchored on the 31st gives Jan 31, Feb 28, Mar 31 — not
Jan 31, Feb 28, Mar 28, which is what chained clamping produces.

### Everything external is behind an interface

`AdProvider`, `BillingProvider`, `AnalyticsProvider`, `FinancialAiService`, `AdConfigProvider`,
`AdImpressionStore`, `KhaataClock`. Each has a no-op or local implementation that the app runs
happily on. No feature outside these packages imports an SDK type, so removing AdMob is a change
to one Hilt binding rather than a search through the UI.

`KhaataClock` is the same idea applied to time: nothing calls `LocalDate.now()` in domain code, so
"what does this budget look like on the 31st" is a test rather than a wait.

### State is a sealed type, not a set of booleans

```kotlin
sealed interface ScreenState<out T> { Loading; Empty; Error; Content<T> }
```

A screen cannot forget the empty state, because it has to handle the branch. `EmptyState` takes
`description` as a required argument, and an action wherever one makes sense — a dead-end empty
screen is where new users decide an app is not for them.

---

## Threading

- Room and file IO on `Dispatchers.IO`, via `withContext` inside the repository or manager.
- Flows are cold and collected with `collectAsStateWithLifecycle`, so nothing runs behind a
  backgrounded screen.
- `SmsTransactionReceiver` uses `goAsync()` and a scoped coroutine, because `onReceive` has a
  few milliseconds on the main thread and parsing plus three queries does not fit in them.
- Nothing blocks on `runBlocking` anywhere in production code.

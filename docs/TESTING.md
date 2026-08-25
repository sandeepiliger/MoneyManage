# Testing

## What actually ran

```
./gradlew -Pkhaata.androidModule=false :core:test
→ 369 tests, 0 failures, 27 test classes
```

That is the real number, from `core/build/test-results/test/*.xml`, on the current commit.

## What did not run

| | Status | Why |
| --- | --- | --- |
| `:app` unit tests (`app/src/test`) | Written, **not run** | Needs the Android Gradle Plugin |
| `:app` instrumentation tests (`app/src/androidTest`) | Written, **not run** | Needs an emulator or device |
| `:app` compilation | **Not attempted** | No Android SDK in this environment |
| Android lint | **Not run** | Same |
| Compose UI tests | **Not written** | See [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) |

`dl.google.com` is blocked by the environment's egress policy, so AGP, the Android SDK and every
AndroidX artifact are unreachable. Nothing in `:app` has been compiled. Expect import and signature
slips on your first real build.

---

## `:core` coverage

| Area | Class | What is pinned |
| --- | --- | --- |
| Money | `MoneyTest`, `MoneyFormatterTest`, `MoneyParserTest` | Scale invariants, mixed-currency rejection, allocation without losing paise, lakh/crore grouping, `₹`/`Rs.`/`50k`/`2 lakh` parsing |
| Balances | `BalanceCalculatorTest` | Opening balance plus postings, transfer signs, net worth, available-to-spend |
| Budgets | `BudgetCalculatorTest` | Period boundaries with month-end clamping, subcategory roll-up, projection, safe daily spend, carry-over that never carries debt |
| Loans | `LoanCalculatorTest` | EMI formula, principal/interest split summing exactly to the principal, final-instalment rounding |
| Cards | `CreditCardCalculatorTest` | Statement cycles, due date rolling into the next month, statement balance by rewinding, minimum due |
| Recurrence | `RecurrenceCalculatorTest` | Anchor-derived occurrences (Jan 31 → Feb 28 → **Mar 31**, not Mar 28), due postings, idempotent watermark |
| Commitments | `CommitmentCalculatorTest` | Frequency/interval composition, **transfers excluded from both sides**, cancelled subscriptions excluded |
| Cashflow | `CashflowAnalyzerTest` | Transfers excluded, category roll-up, savings rate null when there is no income |
| Investments, goals | `InvestmentCalculatorTest`, `GoalCalculatorTest` | Returns, staleness, pacing |
| Categorising | `MerchantNormaliserTest`, `MerchantCategorizerTest`, `DefaultCategoriesTest` | Noise/location token stripping, precedence (user > learned > seeded), **no unreachable seed rules** |
| SMS | `BankSmsParserTest` | OTP/promo/reminder rejection, "Avl Bal" never mistaken for the amount, UPI/NEFT/IMPS/RTGS/ATM/POS/EMI rails |
| Natural language | `NaturalLanguageParserTest` | Multi-amount segmentation, date phrases masked before amount extraction, income nouns outranking generic verbs |
| Insights | `InsightEngineTest` | Every insight carries evidence; thresholds |
| Backup | `BackupSerializerTest`, `CsvTest` | Round-trip, typed failure results, formula-injection guard, per-row rejection |
| Entitlements | `EntitlementManagerTest` | Expiry, grace period, pending grants nothing, core tracking always free |
| Validation | `CategoryValidatorTest` | Per-parent name uniqueness, two-level depth limit |
| Assistant | `LocalFinancialAiServiceTest`, `AiConsentAndConfigTest` | Intent classification, consent gate requiring opt-in *and* entitlement *and* configuration |
| Periods | `ReportPeriodTest` | **March belongs to the financial year that started the previous April** |
| Durations | `IsoPeriodTest` | `P1W`, which `java.time.Period` cannot parse |
| Demo data | `DemoDataGeneratorTest` | Deterministic, every id `demo-` prefixed, internally consistent totals |

### Money correctness specifically

The spec called this out, so to be explicit — these are tested and pass:

- No `Double` anywhere in a money path. `Money` wraps `BigDecimal` and rejects a wrong scale at
  construction.
- `a + b` on different currencies **throws**.
- `allocate()` distributes a remainder without creating or losing paise: splitting ₹100 three ways
  gives 33.34 + 33.33 + 33.33, and the test asserts the parts sum back to the whole.
- EMI schedules: every instalment's principal components sum **exactly** to the loan principal,
  with the final instalment absorbing accumulated rounding. Verified independently against Python's
  `decimal` module during development.
- Percentages return `null` rather than dividing by zero.
- Minor-unit round-trip: `Money → Long → Money` is lossless.

---

## Bugs this testing actually caught

Not hypothetical. Each of these was a real defect found by a failing test:

1. Three seeded merchant rules were **unreachable** — the normaliser stripped the very token they
   keyed on (`more_retail` → `more`, `air_india` → `air`, `urban_company` → `urban`). A test now
   reports every dead rule at once.
2. The EMI schedule ran **13 instalments where 12 were expected** — the test's expectation was
   wrong, not the code, but only recomputing it by hand in Python settled which.
3. Merchant identity was **split by city suffix**: "Swiggy Bangalore" and "Swiggy Mumbai" learned
   as two merchants. Fixed with a location-token list.
4. The natural-language parser read **the digits of a date as an amount** ("spent on 15 March" →
   ₹15). Fixed by masking date phrases, index-preserving, before amount extraction.
5. "I paid 35000 salary" classified as an **expense**. Strong income nouns now outrank generic
   verbs.
6. A budget suggestion included a **subcategory alongside its parent**, so the same spend would
   have counted against two budgets.
7. `displayName` shouted **"OIL"** at the user — a length heuristic mistaking a short word for an
   acronym. Replaced with an explicit acronym set.
8. The AI's "compared to last month" read the **comparison clause as the subject**, shifting the
   period being reported.
9. Demo data had **available balance equal to total assets** — no illiquid account. Fixed by adding
   a PPF account excluded from available balance.

---

## `:app` tests as written

**`TransactionAggregateParityTest`** (instrumentation, real SQLite) — the important one. There are
two implementations of "what is this balance": Kotlin (`BalanceCalculator`) and SQL (the `SUM`/`CASE`
aggregates in `TransactionDao`). The SQL one exists because folding forty thousand rows in Kotlin on
a mid-range phone is visibly slow. Two implementations of one rule always drift, and this drift
produces a quietly wrong balance — the worst failure this app has, because the user cannot detect
it without adding everything up themselves. The test exercises transfers in both directions,
soft-deleted rows, pending rows, and a mixture, against both.

**`BackupViewModelTest`** (JVM, MockK) — the restore state machine: nothing is written before the
user sees the file's contents and picks a mode; the default mode is never replace-everything; a
partial restore is reported as partial rather than as success.

**`KhaataTestRunner`** — swaps in `HiltTestApplication` so instrumentation tests do not run the real
application's startup work first.

Running them, once an SDK is available:

```bash
./gradlew :app:testDebugUnitTest          # JVM
./gradlew :app:connectedDebugAndroidTest  # needs a device or emulator
```

---

## What is not tested

Stated plainly rather than left to be discovered:

- **No Compose UI tests.** Semantics are written for them (`clearAndSetSemantics` on money text,
  labelled progress bars, selectable rows), but no test asserts a screen renders or that a tap does
  what it should.
- **No migration tests**, because there is no migration yet. The pattern is documented in
  [SCHEMA.md](SCHEMA.md).
- **No Play Billing integration test.** The provider is written against the v7 API and has not been
  run against a real Play connection.
- **No AdMob integration test.** `AdSlot` has never rendered.
- **No accessibility audit.** TalkBack has not been run over any screen.
- **No performance measurement.** No benchmark, no baseline profile, no measurement of the claim
  that entry takes two to three seconds.

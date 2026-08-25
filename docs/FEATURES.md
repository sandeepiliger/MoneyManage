# Features

Each entry says what the feature does and the decision behind it. Where the reference apps —
Money Manager (Realbyte), Wallet (BudgetBakers), Monefy — do something differently, that is noted,
along with why this app diverges. No layout, colour, icon, string or interaction flow is taken from
any of them.

---

## Onboarding

Fourteen steps, of which **two are mandatory**: welcome, and adding one account. Everything else can
be skipped, and each screen says what it is for.

A first-run flow that demands a dozen decisions before showing anything is where a large share of
installs are lost. Someone who wants to record a chai should be able to, ninety seconds after
installing, without having chosen a theme.

**Sample data is offered up front.** An empty finance app is impossible to evaluate — you cannot
tell whether the reports are any good with no transactions in them. The demo dataset is realistic
Indian data, labelled throughout, and removable in one tap.

---

## Transaction entry — the core loop

A **custom keypad**, not the system numeric keyboard. Three reasons: it is on screen immediately
with nothing to focus, the keys are far larger than a soft keyboard's, and Save sits inside the
same block so entry finishes without the hand moving.

Two to three seconds for a typical expense: open, type amount, confirm the suggested category, save.

The category is suggested from the merchant via `MerchantCategorizer` — user rules beat learned
rules beat the ~180 shipped Indian merchants. **A null suggestion leaves the picker unselected
rather than guessing**: a wrong preselection the user has to notice and undo costs more than the
tap it saved.

There is deliberately **no ad anywhere near this screen** — not on the keypad, not after saving.

### Natural-language entry

"350 chai with team yesterday" → a *draft* transaction with amount, category, note and date filled
in. **The user always confirms.** Nothing is written from a parsed sentence without a look at it;
the parser is good, not infallible, and a financial record created from a misreading is worse than
no shortcut at all.

---

## Categories

**Two levels only.** The reference apps allow arbitrarily deep trees, which looks flexible and makes
the picker slow to scan and reports hard to read. A fixed depth keeps categorising a one-tap
decision, which is what matters when you are standing at a counter.

The seeded set is chosen for how money is actually spent in urban and semi-urban India — auto,
FASTag, cooking gas, society maintenance, household help, parents, gifts and festivals — rather
than for accounting completeness. `CategoryGroup.FAMILY` exists because supporting parents and
children is a normal, sizeable line item here and does not belong under "Lifestyle".

**Hiding is offered before deleting, everywhere.** A deleted category strips the label off past
transactions with no way back; hiding achieves what almost everyone actually wants ("stop showing me
this") with nothing lost. When a delete *is* chosen, the dialog counts the affected transactions
first — the consequence appears before the button, not after it.

---

## Budgets

Rules, not materialised periods — the period containing any date is computed on demand.

The screen leads with **pacing rather than a total**: a projection of where the month lands at the
current rate, and a safe-daily-spend figure. "You have ₹4,000 left" on the 8th and on the 27th are
very different situations, and a bar that only fills up says nothing about which one you are in.

`BudgetStatus` is stated in words — on track, spending fast, nearly used up, fully used, over
budget — so the state never depends on colour.

Carry-over **never carries debt**. Rolling an overspend into next month turns one bad month into a
punishment that compounds, and users respond by deleting the budget.

Transfers never count against a budget.

---

## Recurring

Rent, salary, EMIs, SIPs, insurance.

**Auto-posting is off by default.** This is the most consequential default in the app: the app knows
rent was *due* on the 5th, not that it actually went out. A balance built on assumed payments is one
the user cannot reconcile against their bank statement, and rebuilding that trust costs more than
the tap it saved.

So the screen leads with **what is waiting for the user** — occurrences that have come due and need
confirming. "Record it" and "Didn't happen" are given equal visual weight deliberately; making
confirm the obvious button would push people into recording payments that never left the account,
which is the exact failure the whole flow exists to avoid.

Occurrences are derived from the anchor date, never chained: a rule anchored on the 31st gives
Jan 31 → Feb 28 → **Mar 31**, not Mar 28. Chained clamping is the bug in most implementations.

---

## Subscriptions

The **yearly figure gets the same weight as the monthly one** — on every card, and in the editor
while the form is still open, when it can still change the answer. ₹649 a month and ₹7,788 a year
are the same fact, and only the second one changes anybody's mind. The reference apps tend to show
the monthly figure alone, which is the number that makes a subscription feel affordable.

Cancelling **marks a service cancelled rather than deleting it**, so past yearly totals stay
correct. The dialog says plainly that this only updates the record here and does not cancel anything
with the service — a user could reasonably expect otherwise.

---

## Credit cards

Leads with the **statement balance and its due date**, not today's running outstanding. Those are
different numbers, and the one the user is about to be charged for is the statement balance.

Utilisation is stated in words alongside the percentage, so "high" is never inferred from a bar's
colour. When a card is revolving, the app states what that costs — a fact about interest, not advice
about what to do.

The outstanding amount is **not stored**: it is the linked account's balance, derived from
transactions like everything else. Storing it twice guarantees the two eventually disagree.

---

## Loans

Standard EMI amortisation in `BigDecimal`, with the schedule's principal components summing
**exactly** to the principal — the final instalment absorbs accumulated rounding.

The detail screen shows the principal/interest split per instalment, because "where does my EMI
actually go" is the question people have and few apps answer.

**No advice.** The screens state facts about amortisation. They do not recommend refinancing,
prepaying, or taking a loan.

---

## Investments and goals

Investments are **manually valued** — there is no price feed. The screen says so, and flags any
holding whose valuation has gone stale. Showing a two-month-old figure as though it were current
would be the most misleading thing this screen could do.

Goals lead with **the monthly figure needed to get there**, not the percentage. "34% complete" is a
status; "₹8,400 a month" is a decision. Achieved goals sink to the bottom — they are a record, not
a task.

---

## Reports

Seven fixed periods rather than a date picker as the primary control, because nearly every question
people ask is "this month", "last month" or "this year" and a picker makes each cost four taps.
**The Indian financial year is one of them**, because that is the period that matters at tax time
and it does not start in January.

Every chart is paired with the same figures as text — a donut a screen reader cannot describe is
not a report. Change against the previous period is worded as a plain fact ("₹2,400 more than the
period before"), never as praise or a telling-off: a month with a wedding in it is not a failure.

The screen states outright, at the bottom, that transfers are excluded. A user who moved ₹50,000 to
a fixed deposit and sees it counted as spending concludes the app is broken — and they would be
right.

---

## Insights

Rule-based, on-device. **Every insight carries its evidence**, and the UI always shows it. An app
that tells you something about your money without showing where it came from is asking to be
trusted on a subject where trust should be earned per-statement.

---

## The assistant

Local rule-based engine on every plan; cloud optional, opt-in, AI Pro. It answers questions about
the user's own data and shows the transactions behind each answer. It never executes a financial
action and never gives regulated financial advice. See [AI_PROVIDER.md](AI_PROVIDER.md).

---

## SMS import

Off by default; the receiver does not exist as a registered component until the user opts in.
Parses UPI, NEFT, IMPS, RTGS, ATM, POS and EMI messages **on the device**, never stores the message
body, and lands every result as pending for confirmation.

A message it cannot match to an account is **refused rather than filed against a guess**. Putting a
transaction on the wrong account silently corrupts two balances, and the user has no way to see that
it happened.

---

## Backup

A **file**, not a sync. The user creates it, the share sheet hands it to whatever they already trust,
and the app never learns where it went. Slower than a sync toggle, and the only design consistent
with never uploading someone's ledger.

Restore shows what a file contains and asks how to merge **before writing anything**, defaults to
the least destructive mode, and rejects individual bad rows with a reason rather than failing a
whole restore. One malformed transaction in a 4,000-row file should skip that row and say so.

CSV export guards against formula injection — a merchant name beginning with `=` is code when
opened in Excel.

---

## Design system

Original throughout. Two decisions worth stating:

**Income is teal, expense is rose** — not green and red. Red–green colour blindness is the common
one, and red frames every purchase as a mistake. Buying groceries is not an error.

**Colour never carries meaning alone.** Every state is also stated in words: over budget, high
utilisation, hidden, paused, renewing soon, stale valuation. Amounts carry a sign, so direction
never depends on tint.

Light and dark both, Material 3, minimum 48dp touch targets, and every screen has loading, empty,
error and retry states — `ScreenState` is a sealed type, so a screen cannot forget one.

package ai.labs32.khaata.core.demo

import ai.labs32.khaata.core.categorize.DefaultCategories as C
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.model.Budget
import ai.labs32.khaata.core.model.BudgetPeriod
import ai.labs32.khaata.core.model.CreditCard
import ai.labs32.khaata.core.model.Frequency
import ai.labs32.khaata.core.model.Goal
import ai.labs32.khaata.core.model.Investment
import ai.labs32.khaata.core.model.InvestmentKind
import ai.labs32.khaata.core.model.Loan
import ai.labs32.khaata.core.model.RecurringRule
import ai.labs32.khaata.core.model.Subscription
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.TransactionSource
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random

/**
 * Builds a realistic demo dataset.
 *
 * Two uses: letting someone see what the app looks like populated before committing their own
 * data, and giving tests and screenshots a stable, non-trivial ledger to work against.
 *
 * Everything here is fictional. The bank names are real institutions because a demo with
 * "Bank A" and "Bank B" does not communicate anything, but every account number, balance and
 * transaction is invented. Nothing is derived from a real person's records.
 *
 * Generation is seeded, so the same [seed] always produces the same data — screenshots stay
 * stable and a failing test can be reproduced.
 */
class DemoDataGenerator(
    private val currency: CurrencyCode = CurrencyCode.INR,
    private val seed: Long = DEFAULT_SEED,
) {

    /**
     * Generates [months] of history ending at [asOf].
     *
     * @param asOf the "today" the data is built around.
     * @param months how much history to synthesise. Six is enough for month-over-month trends
     *   and comparisons to have something to say.
     */
    fun generate(asOf: LocalDate, months: Int = 6): DemoDataset {
        require(months in 1..24) { "Demo history must be 1-24 months, got $months" }
        val random = Random(seed)
        val now = Instant.EPOCH

        val accounts = buildAccounts(now)
        val transactions = buildTransactions(asOf, months, random, now)

        return DemoDataset(
            accounts = accounts,
            transactions = transactions,
            budgets = buildBudgets(asOf),
            recurringRules = buildRecurring(asOf),
            subscriptions = buildSubscriptions(asOf),
            creditCards = buildCreditCards(),
            loans = buildLoans(asOf),
            investments = buildInvestments(asOf),
            goals = buildGoals(asOf),
        )
    }

    // ---- Accounts ----------------------------------------------------------------------------

    private fun buildAccounts(now: Instant): List<Account> = listOf(
        Account(
            id = ACC_HDFC,
            name = "HDFC Bank",
            type = AccountType.SAVINGS,
            currency = currency,
            openingBalance = Money.of("85000", currency),
            institution = "HDFC Bank",
            maskedIdentifier = "4321",
            colorSeed = 0,
            sortOrder = 0,
            createdAt = now,
            updatedAt = now,
        ),
        Account(
            id = ACC_ICICI,
            name = "ICICI Bank",
            type = AccountType.SAVINGS,
            currency = currency,
            openingBalance = Money.of("42000", currency),
            institution = "ICICI Bank",
            maskedIdentifier = "8899",
            colorSeed = 1,
            sortOrder = 1,
            createdAt = now,
            updatedAt = now,
        ),
        Account(
            id = ACC_CASH,
            name = "Cash",
            type = AccountType.CASH,
            currency = currency,
            openingBalance = Money.of("3500", currency),
            colorSeed = 2,
            sortOrder = 2,
            createdAt = now,
            updatedAt = now,
        ),
        Account(
            id = ACC_CARD,
            name = "HDFC Credit Card",
            type = AccountType.CREDIT_CARD,
            currency = currency,
            openingBalance = Money.zero(currency),
            institution = "HDFC Bank",
            maskedIdentifier = "7712",
            colorSeed = 3,
            sortOrder = 3,
            createdAt = now,
            updatedAt = now,
        ),
        Account(
            id = ACC_UPI_WALLET,
            name = "Paytm Wallet",
            type = AccountType.WALLET,
            currency = currency,
            openingBalance = Money.of("1200", currency),
            institution = "Paytm",
            colorSeed = 4,
            sortOrder = 4,
            createdAt = now,
            updatedAt = now,
        ),
        // Counts towards net worth but not towards "available to spend" — the distinction the
        // dashboard is built around, and one a demo has to actually show to explain.
        Account(
            id = ACC_PPF,
            name = "PPF",
            type = AccountType.INVESTMENT,
            currency = currency,
            openingBalance = Money.of("512300", currency),
            institution = "SBI",
            includeInNetWorth = true,
            includeInAvailableBalance = false,
            colorSeed = 5,
            sortOrder = 5,
            createdAt = now,
            updatedAt = now,
        ),
    )

    // ---- Transactions ------------------------------------------------------------------------

    /**
     * Synthesises a month-by-month ledger.
     *
     * Fixed commitments land on their usual dates; discretionary spending is scattered through
     * the month with varied amounts, so category totals differ month to month and the trend and
     * insight screens have something real to work with.
     */
    private fun buildTransactions(
        asOf: LocalDate,
        months: Int,
        random: Random,
        now: Instant,
    ): List<Transaction> {
        val transactions = mutableListOf<Transaction>()
        var sequence = 0

        fun add(
            type: TransactionType,
            amount: String,
            accountId: String,
            categoryId: String?,
            merchant: String?,
            date: LocalDate,
            transferTo: String? = null,
        ) {
            if (date.isAfter(asOf)) return
            transactions += Transaction(
                id = "demo-txn-${sequence++}",
                type = type,
                amount = Money.of(amount, currency),
                accountId = accountId,
                transferAccountId = transferTo,
                categoryId = categoryId,
                merchant = merchant,
                occurredOn = date,
                source = TransactionSource.DEMO,
                createdAt = now,
                updatedAt = now,
            )
        }

        val firstMonth = asOf.withDayOfMonth(1).minusMonths((months - 1).toLong())

        for (monthIndex in 0 until months) {
            val monthStart = firstMonth.plusMonths(monthIndex.toLong())
            val lengthOfMonth = monthStart.lengthOfMonth()
            fun day(dayOfMonth: Int): LocalDate =
                monthStart.withDayOfMonth(dayOfMonth.coerceAtMost(lengthOfMonth))

            // ---- Fixed monthly commitments ----
            add(TransactionType.INCOME, "112000", ACC_HDFC, C.INCOME_SALARY, "Salary", day(1))
            add(TransactionType.EXPENSE, "32000", ACC_HDFC, C.RENT, "Landlord", day(3))
            add(TransactionType.EXPENSE, "16607.15", ACC_HDFC, C.EMI, "Personal Loan EMI", day(5))
            add(TransactionType.EXPENSE, "10000", ACC_HDFC, C.SIP, "SIP - Index Fund", day(7))
            add(TransactionType.EXPENSE, "2400", ACC_HDFC, C.MAINTENANCE, "Society Maintenance", day(8))
            add(TransactionType.EXPENSE, "999", ACC_HDFC, C.INTERNET, "ACT Fibernet", day(10))
            add(TransactionType.EXPENSE, "749", ACC_HDFC, C.MOBILE_RECHARGE, "Airtel", day(12))
            add(TransactionType.EXPENSE, "649", ACC_CARD, C.SUBSCRIPTIONS, "Netflix", day(20))
            add(TransactionType.EXPENSE, "119", ACC_CARD, C.SUBSCRIPTIONS, "Spotify", day(22))

            // Electricity swings with the season, which makes the bills trend interesting.
            val electricity = 1800 + random.nextInt(0, 1600)
            add(TransactionType.EXPENSE, electricity.toString(), ACC_HDFC, C.ELECTRICITY, "BESCOM", day(15))

            // ---- Discretionary spending ----
            repeat(random.nextInt(6, 11)) {
                val amount = random.nextInt(280, 1250)
                add(
                    TransactionType.EXPENSE, amount.toString(), ACC_CARD, C.FOOD_DELIVERY,
                    if (random.nextBoolean()) "Swiggy" else "Zomato",
                    day(random.nextInt(1, lengthOfMonth + 1)),
                )
            }
            repeat(random.nextInt(3, 6)) {
                val amount = random.nextInt(1400, 4200)
                add(
                    TransactionType.EXPENSE, amount.toString(), ACC_CARD, C.GROCERIES,
                    listOf("BigBasket", "DMart", "Blinkit", "Zepto").random(random),
                    day(random.nextInt(1, lengthOfMonth + 1)),
                )
            }
            repeat(random.nextInt(4, 9)) {
                val amount = random.nextInt(90, 420)
                add(
                    TransactionType.EXPENSE, amount.toString(), ACC_UPI_WALLET, C.CAB,
                    listOf("Uber", "Ola", "Rapido").random(random),
                    day(random.nextInt(1, lengthOfMonth + 1)),
                )
            }
            repeat(random.nextInt(2, 4)) {
                val amount = random.nextInt(1600, 3400)
                add(
                    TransactionType.EXPENSE, amount.toString(), ACC_CARD, C.FUEL,
                    listOf("Indian Oil", "Bharat Petroleum", "Shell").random(random),
                    day(random.nextInt(1, lengthOfMonth + 1)),
                )
            }
            repeat(random.nextInt(1, 4)) {
                val amount = random.nextInt(700, 6500)
                add(
                    TransactionType.EXPENSE, amount.toString(), ACC_CARD, C.SHOPPING,
                    listOf("Amazon", "Flipkart", "Myntra", "Ajio").random(random),
                    day(random.nextInt(1, lengthOfMonth + 1)),
                )
            }
            repeat(random.nextInt(5, 12)) {
                val amount = random.nextInt(40, 260)
                add(
                    TransactionType.EXPENSE, amount.toString(), ACC_CASH, C.TEA_COFFEE,
                    listOf("Chai Point", "Third Wave Coffee", "Local Tea Stall").random(random),
                    day(random.nextInt(1, lengthOfMonth + 1)),
                )
            }

            // ---- Movements between the user's own accounts ----
            add(TransactionType.TRANSFER, "25000", ACC_HDFC, null, null, day(9), transferTo = ACC_ICICI)
            add(TransactionType.EXPENSE, "3000", ACC_HDFC, null, "ATM Withdrawal", day(11), transferTo = null)
            // Paying off the credit card is a transfer, not a new expense — counting it as
            // spending would double-count everything already charged to the card.
            add(TransactionType.TRANSFER, "18000", ACC_HDFC, null, null, day(14), transferTo = ACC_CARD)
        }
        return transactions.sortedBy { it.occurredOn }
    }

    // ---- Everything else ---------------------------------------------------------------------

    private fun buildBudgets(asOf: LocalDate): List<Budget> {
        val anchor = asOf.withDayOfMonth(1).minusMonths(5)
        return listOf(
            Budget(
                id = "demo-budget-food",
                name = "Food & Drink",
                limit = Money.of("14000", currency),
                period = BudgetPeriod.MONTHLY,
                categoryIds = setOf(C.FOOD),
                anchorDate = anchor,
                alertThresholdPercent = 85,
                sortOrder = 0,
            ),
            Budget(
                id = "demo-budget-transport",
                name = "Transport",
                limit = Money.of("9000", currency),
                period = BudgetPeriod.MONTHLY,
                categoryIds = setOf(C.TRANSPORT),
                anchorDate = anchor,
                sortOrder = 1,
            ),
            Budget(
                id = "demo-budget-shopping",
                name = "Shopping",
                limit = Money.of("8000", currency),
                period = BudgetPeriod.MONTHLY,
                categoryIds = setOf(C.SHOPPING),
                anchorDate = anchor,
                rollsOver = true,
                sortOrder = 2,
            ),
        )
    }

    private fun buildRecurring(asOf: LocalDate): List<RecurringRule> {
        val start = asOf.withDayOfMonth(1).minusMonths(5)
        return listOf(
            RecurringRule(
                id = "demo-rec-salary",
                name = "Salary",
                type = TransactionType.INCOME,
                amount = Money.of("112000", currency),
                accountId = ACC_HDFC,
                categoryId = C.INCOME_SALARY,
                frequency = Frequency.MONTHLY,
                startDate = start.withDayOfMonth(1),
                lastPostedOn = asOf.withDayOfMonth(1),
            ),
            RecurringRule(
                id = "demo-rec-rent",
                name = "Rent",
                type = TransactionType.EXPENSE,
                amount = Money.of("32000", currency),
                accountId = ACC_HDFC,
                categoryId = C.RENT,
                merchant = "Landlord",
                frequency = Frequency.MONTHLY,
                startDate = start.withDayOfMonth(3),
                reminderDaysBefore = 2,
            ),
            RecurringRule(
                id = "demo-rec-sip",
                name = "SIP - Index Fund",
                type = TransactionType.EXPENSE,
                amount = Money.of("10000", currency),
                accountId = ACC_HDFC,
                categoryId = C.SIP,
                frequency = Frequency.MONTHLY,
                startDate = start.withDayOfMonth(7),
            ),
        )
    }

    private fun buildSubscriptions(asOf: LocalDate): List<Subscription> = listOf(
        Subscription(
            id = "demo-sub-netflix",
            name = "Netflix",
            amount = Money.of("649", currency),
            cycle = Frequency.MONTHLY,
            nextPaymentDate = nextOccurrence(asOf, 20),
            startedOn = asOf.minusMonths(14),
            categoryId = C.SUBSCRIPTIONS,
            accountId = ACC_CARD,
            merchantKey = "netflix",
        ),
        Subscription(
            id = "demo-sub-spotify",
            name = "Spotify",
            amount = Money.of("119", currency),
            cycle = Frequency.MONTHLY,
            nextPaymentDate = nextOccurrence(asOf, 22),
            startedOn = asOf.minusMonths(9),
            categoryId = C.SUBSCRIPTIONS,
            accountId = ACC_CARD,
            merchantKey = "spotify",
        ),
        Subscription(
            id = "demo-sub-prime",
            name = "Amazon Prime",
            amount = Money.of("1499", currency),
            cycle = Frequency.YEARLY,
            nextPaymentDate = asOf.plusMonths(4).withDayOfMonth(11),
            startedOn = asOf.minusMonths(8),
            categoryId = C.SUBSCRIPTIONS,
            accountId = ACC_CARD,
            merchantKey = "amazon",
        ),
        Subscription(
            id = "demo-sub-googleone",
            name = "Google One",
            amount = Money.of("130", currency),
            cycle = Frequency.MONTHLY,
            nextPaymentDate = nextOccurrence(asOf, 28),
            startedOn = asOf.minusMonths(20),
            categoryId = C.SUBSCRIPTIONS,
            accountId = ACC_CARD,
            merchantKey = "google_one",
        ),
    )

    private fun buildCreditCards(): List<CreditCard> = listOf(
        CreditCard(
            id = "demo-card-hdfc",
            accountId = ACC_CARD,
            cardName = "HDFC Credit Card",
            issuer = "HDFC Bank",
            creditLimit = Money.of("250000", currency),
            statementDayOfMonth = 25,
            dueDayOfMonth = 14,
            lastFourDigits = "7712",
        ),
    )

    private fun buildLoans(asOf: LocalDate): List<Loan> = listOf(
        Loan(
            id = "demo-loan-personal",
            name = "Personal Loan",
            lender = "HDFC Bank",
            principal = Money.of("500000", currency),
            annualInterestRatePercent = BigDecimal("12"),
            tenureMonths = 36,
            startDate = asOf.minusMonths(14).withDayOfMonth(5),
            emiDayOfMonth = 5,
            categoryId = C.EMI,
        ),
    )

    private fun buildInvestments(asOf: LocalDate): List<Investment> = listOf(
        Investment(
            id = "demo-inv-index",
            name = "Nifty 50 Index Fund",
            kind = InvestmentKind.SIP,
            investedAmount = Money.of("240000", currency),
            currentValue = Money.of("281400", currency),
            startedOn = asOf.minusMonths(24),
            valuedOn = asOf.minusDays(3),
            folioOrSymbol = "DEMO-0001",
        ),
        Investment(
            id = "demo-inv-fd",
            name = "Fixed Deposit",
            kind = InvestmentKind.FIXED_DEPOSIT,
            investedAmount = Money.of("200000", currency),
            currentValue = Money.of("214800", currency),
            startedOn = asOf.minusMonths(13),
            valuedOn = asOf.minusDays(3),
        ),
        Investment(
            id = "demo-inv-gold",
            name = "Sovereign Gold Bond",
            kind = InvestmentKind.GOLD,
            investedAmount = Money.of("100000", currency),
            currentValue = Money.of("118600", currency),
            startedOn = asOf.minusMonths(19),
            valuedOn = asOf.minusDays(10),
        ),
    )

    private fun buildGoals(asOf: LocalDate): List<Goal> = listOf(
        Goal(
            id = "demo-goal-emergency",
            name = "Emergency Fund",
            targetAmount = Money.of("600000", currency),
            currentAmount = Money.of("342000", currency),
            targetDate = asOf.plusMonths(14),
            startedOn = asOf.minusMonths(10),
            iconKey = "shield",
            colorSeed = 0,
        ),
        Goal(
            id = "demo-goal-trip",
            name = "Ladakh Trip",
            targetAmount = Money.of("150000", currency),
            currentAmount = Money.of("48000", currency),
            targetDate = asOf.plusMonths(7),
            startedOn = asOf.minusMonths(3),
            iconKey = "travel",
            colorSeed = 1,
        ),
        Goal(
            id = "demo-goal-laptop",
            name = "New Laptop",
            targetAmount = Money.of("120000", currency),
            currentAmount = Money.of("120000", currency),
            targetDate = asOf.minusMonths(1),
            startedOn = asOf.minusMonths(8),
            achievedOn = asOf.minusMonths(1),
            iconKey = "laptop",
            colorSeed = 2,
        ),
    )

    /** The next occurrence of [dayOfMonth] on or after [asOf]. */
    private fun nextOccurrence(asOf: LocalDate, dayOfMonth: Int): LocalDate {
        val thisMonth = asOf.withDayOfMonth(dayOfMonth.coerceAtMost(asOf.lengthOfMonth()))
        if (!thisMonth.isBefore(asOf)) return thisMonth
        val next = asOf.plusMonths(1)
        return next.withDayOfMonth(dayOfMonth.coerceAtMost(next.lengthOfMonth()))
    }

    companion object {
        const val ACC_HDFC = "demo-acc-hdfc"
        const val ACC_ICICI = "demo-acc-icici"
        const val ACC_CASH = "demo-acc-cash"
        const val ACC_CARD = "demo-acc-card"
        const val ACC_UPI_WALLET = "demo-acc-wallet"
        const val ACC_PPF = "demo-acc-ppf"

        /** Fixed so demo data, screenshots and tests are reproducible. */
        const val DEFAULT_SEED = 32L

        /** Marks every demo row, so exiting demo mode can remove exactly what it added. */
        const val DEMO_ID_PREFIX = "demo-"
    }
}

/** A complete generated dataset. */
data class DemoDataset(
    val accounts: List<Account>,
    val transactions: List<Transaction>,
    val budgets: List<Budget>,
    val recurringRules: List<RecurringRule>,
    val subscriptions: List<Subscription>,
    val creditCards: List<CreditCard>,
    val loans: List<Loan>,
    val investments: List<Investment>,
    val goals: List<Goal>,
) {
    val totalRecords: Int
        get() = accounts.size + transactions.size + budgets.size + recurringRules.size +
            subscriptions.size + creditCards.size + loans.size + investments.size + goals.size
}

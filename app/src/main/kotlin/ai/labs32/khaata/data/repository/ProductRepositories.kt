package ai.labs32.khaata.data.repository

import ai.labs32.khaata.core.calc.CreditCardCalculator
import ai.labs32.khaata.core.calc.CreditCardStatus
import ai.labs32.khaata.core.calc.GoalCalculator
import ai.labs32.khaata.core.calc.GoalProgress
import ai.labs32.khaata.core.calc.InvestmentCalculator
import ai.labs32.khaata.core.calc.LoanCalculator
import ai.labs32.khaata.core.calc.LoanStatus
import ai.labs32.khaata.core.calc.PortfolioSummary
import ai.labs32.khaata.core.calc.RecurrenceCalculator
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.database.dao.CreditCardDao
import ai.labs32.khaata.core.database.dao.GoalDao
import ai.labs32.khaata.core.database.dao.InvestmentDao
import ai.labs32.khaata.core.database.dao.LoanDao
import ai.labs32.khaata.core.database.dao.RecurringRuleDao
import ai.labs32.khaata.core.database.dao.SubscriptionDao
import ai.labs32.khaata.core.database.toDomainOrNull
import ai.labs32.khaata.core.database.toEntity
import ai.labs32.khaata.core.model.CreditCard
import ai.labs32.khaata.core.model.Frequency
import ai.labs32.khaata.core.model.Goal
import ai.labs32.khaata.core.model.Investment
import ai.labs32.khaata.core.model.Loan
import ai.labs32.khaata.core.model.OccurrenceKind
import ai.labs32.khaata.core.model.RecurringRule
import ai.labs32.khaata.core.model.ScheduledOccurrence
import ai.labs32.khaata.core.model.Subscription
import ai.labs32.khaata.core.model.TransactionSource
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.sumOfMoney
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// =============================================================================================
// Recurring transactions
// =============================================================================================

@Singleton
class RecurringRepository @Inject constructor(
    private val recurringDao: RecurringRuleDao,
    private val transactionRepository: TransactionRepository,
    private val clock: KhaataClock,
) {

    fun observeActive(): Flow<List<RecurringRule>> =
        recurringDao.observeActive().map { list -> list.mapNotNull { it.toDomainOrNull() } }

    fun observeAll(): Flow<List<RecurringRule>> =
        recurringDao.observeAll().map { list -> list.mapNotNull { it.toDomainOrNull() } }

    suspend fun getAll(): List<RecurringRule> =
        recurringDao.getAll().mapNotNull { it.toDomainOrNull() }

    suspend fun findById(id: String): RecurringRule? =
        recurringDao.findById(id)?.toDomainOrNull()

    /** Upcoming occurrences within [days], for reminders and the dashboard. */
    fun observeUpcoming(days: Int = 30): Flow<List<ScheduledOccurrence>> =
        observeActive().map { RecurrenceCalculator.upcomingFromRules(it, clock.today(), days) }

    /**
     * Occurrences that have come due on rules the user chose to confirm manually.
     *
     * These are the whole point of [RecurringRule.autoPost] defaulting to off: the app knows rent
     * was due on the 5th but not whether it actually went out, so it asks instead of asserting.
     */
    fun observeAwaitingConfirmation(): Flow<List<DueOccurrence>> =
        observeActive().map { rules ->
            val today = clock.today()
            rules.filterNot { it.autoPost }
                .flatMap { rule ->
                    RecurrenceCalculator.duePostings(rule, today)
                        .map { DueOccurrence(rule = rule, dueOn = it) }
                }
                .sortedBy { it.dueOn }
        }

    /**
     * Marks an occurrence handled without writing a transaction.
     *
     * For the month the gym was closed, or the EMI already captured from an SMS. Advancing
     * `lastPostedOn` is the only side effect, so the reminder stops without inventing a payment
     * that never happened.
     */
    suspend fun skipOccurrence(ruleId: String, date: LocalDate) =
        recurringDao.markPosted(ruleId, date)

    suspend fun create(rule: RecurringRule): String {
        val withId = rule.copy(id = rule.id.ifBlank { UUID.randomUUID().toString() })
        recurringDao.upsert(withId.toEntity())
        return withId.id
    }

    suspend fun update(rule: RecurringRule) = recurringDao.upsert(rule.toEntity())

    suspend fun setActive(id: String, active: Boolean) {
        findById(id)?.let { recurringDao.upsert(it.copy(isActive = active).toEntity()) }
    }

    suspend fun delete(id: String) {
        recurringDao.findById(id)?.let { recurringDao.delete(it) }
    }

    /**
     * Writes any occurrences that are due but not yet in the ledger.
     *
     * Only rules with [RecurringRule.autoPost] are posted automatically; the rest surface as
     * reminders for the user to confirm. Posting is keyed off `lastPostedOn`, so running this
     * twice in a day cannot create a duplicate rent entry.
     *
     * @return how many transactions were written.
     */
    suspend fun postDueTransactions(): Int {
        val today = clock.today()
        var posted = 0

        for (rule in getAll().filter { it.isActive && it.autoPost }) {
            val due = RecurrenceCalculator.duePostings(rule, today)
            for (date in due) {
                transactionRepository.create(
                    type = rule.type,
                    amount = rule.amount,
                    accountId = rule.accountId,
                    categoryId = rule.categoryId,
                    transferAccountId = rule.transferAccountId,
                    merchant = rule.merchant,
                    note = rule.note,
                    occurredOn = date,
                    source = TransactionSource.RECURRING,
                    recurringRuleId = rule.id,
                    learnCategory = false,
                )
                posted++
            }
            due.maxOrNull()?.let { recurringDao.markPosted(rule.id, it) }
        }
        return posted
    }

    /** Writes a single occurrence the user confirmed from a reminder. */
    suspend fun postOccurrence(ruleId: String, date: LocalDate): String? {
        val rule = findById(ruleId) ?: return null
        val id = transactionRepository.create(
            type = rule.type,
            amount = rule.amount,
            accountId = rule.accountId,
            categoryId = rule.categoryId,
            transferAccountId = rule.transferAccountId,
            merchant = rule.merchant,
            note = rule.note,
            occurredOn = date,
            source = TransactionSource.RECURRING,
            recurringRuleId = rule.id,
            learnCategory = false,
        )
        recurringDao.markPosted(ruleId, date)
        return id
    }

    suspend fun upsertAll(rules: List<RecurringRule>) =
        recurringDao.upsertAll(rules.map { it.toEntity() })

    suspend fun deleteAll() = recurringDao.deleteAll()

    suspend fun deleteDemoData() = recurringDao.deleteDemoData()
}

/** A recurring occurrence that has come due and is waiting for the user to confirm it. */
data class DueOccurrence(val rule: RecurringRule, val dueOn: LocalDate)

// =============================================================================================
// Subscriptions
// =============================================================================================

@Singleton
class SubscriptionRepository @Inject constructor(
    private val subscriptionDao: SubscriptionDao,
    private val clock: KhaataClock,
) {

    fun observeActive(): Flow<List<Subscription>> =
        subscriptionDao.observeActive().map { list -> list.mapNotNull { it.toDomainOrNull() } }

    fun observeAll(): Flow<List<Subscription>> =
        subscriptionDao.observeAll().map { list -> list.mapNotNull { it.toDomainOrNull() } }

    suspend fun getAll(): List<Subscription> =
        subscriptionDao.getAll().mapNotNull { it.toDomainOrNull() }

    suspend fun findById(id: String): Subscription? =
        subscriptionDao.findById(id)?.toDomainOrNull()

    /** Total cost of active subscriptions, normalised per month and per year. */
    fun observeCostSummary(currency: CurrencyCode = CurrencyCode.DEFAULT): Flow<SubscriptionCost> =
        observeActive().map { subscriptions ->
            SubscriptionCost(
                count = subscriptions.size,
                perMonth = subscriptions.sumOfMoney(currency) { it.monthlyEquivalent() },
                perYear = subscriptions.sumOfMoney(currency) { it.yearlyEquivalent() },
            )
        }

    fun observeUpcoming(days: Int = 30): Flow<List<ScheduledOccurrence>> =
        observeActive().map {
            RecurrenceCalculator.upcomingFromSubscriptions(it, clock.today(), days)
        }

    suspend fun create(
        name: String,
        amount: Money,
        cycle: Frequency,
        nextPaymentDate: LocalDate,
        categoryId: String?,
        accountId: String?,
        reminderDaysBefore: Int = 2,
        notes: String? = null,
    ): String {
        val subscription = Subscription(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            amount = amount,
            cycle = cycle,
            nextPaymentDate = nextPaymentDate,
            startedOn = clock.today(),
            categoryId = categoryId,
            accountId = accountId,
            merchantKey = ai.labs32.khaata.core.categorize.MerchantNormaliser.normalise(name),
            reminderDaysBefore = reminderDaysBefore,
            notes = notes,
        )
        subscriptionDao.upsert(subscription.toEntity())
        return subscription.id
    }

    suspend fun update(subscription: Subscription) = subscriptionDao.upsert(subscription.toEntity())

    /** Marks a subscription cancelled, keeping it for historical cost reporting. */
    suspend fun cancel(id: String) {
        findById(id)?.let {
            subscriptionDao.upsert(
                it.copy(cancelledOn = clock.today(), isActive = false).toEntity(),
            )
        }
    }

    suspend fun delete(id: String) {
        subscriptionDao.findById(id)?.let { subscriptionDao.delete(it) }
    }

    /** Rolls the next payment date forward past today, after a charge has been recorded. */
    suspend fun advancePastDue() {
        val today = clock.today()
        for (subscription in getAll().filter { it.isActive && it.nextPaymentDate.isBefore(today) }) {
            val next = RecurrenceCalculator.advanceSubscription(subscription, today)
            subscriptionDao.updateNextPaymentDate(subscription.id, next)
        }
    }

    /** Matches an imported transaction to a tracked subscription. */
    suspend fun findByMerchantKey(merchantKey: String): Subscription? =
        subscriptionDao.findByMerchantKey(merchantKey)?.toDomainOrNull()

    suspend fun upsertAll(subscriptions: List<Subscription>) =
        subscriptionDao.upsertAll(subscriptions.map { it.toEntity() })

    suspend fun deleteAll() = subscriptionDao.deleteAll()

    suspend fun deleteDemoData() = subscriptionDao.deleteDemoData()
}

data class SubscriptionCost(val count: Int, val perMonth: Money, val perYear: Money)

// =============================================================================================
// Credit cards
// =============================================================================================

@Singleton
class CreditCardRepository @Inject constructor(
    private val creditCardDao: CreditCardDao,
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val clock: KhaataClock,
) {

    fun observeActive(): Flow<List<CreditCard>> =
        creditCardDao.observeActive().map { list -> list.mapNotNull { it.toDomainOrNull() } }

    suspend fun getAll(): List<CreditCard> = creditCardDao.getAll().mapNotNull { it.toDomainOrNull() }

    suspend fun findById(id: String): CreditCard? = creditCardDao.findById(id)?.toDomainOrNull()

    suspend fun findByAccountId(accountId: String): CreditCard? =
        creditCardDao.findByAccountId(accountId)?.toDomainOrNull()

    /**
     * Every card with its statement position.
     *
     * Only the current and previous statement cycles are loaded, which is all the calculation
     * needs and keeps the query bounded regardless of how long the card has been held.
     */
    fun observeStatuses(): Flow<List<CreditCardStatus>> {
        val today = clock.today()
        return combine(
            observeActive(),
            accountRepository.observeBalances(),
            transactionRepository.observeInRange(
                DateRange(today.minusMonths(3), today.plusMonths(1)),
            ),
        ) { cards, balances, transactions ->
            val balanceByAccount = balances.associateBy { it.account.id }
            cards.mapNotNull { card ->
                val balance = balanceByAccount[card.accountId] ?: return@mapNotNull null
                CreditCardCalculator.status(card, balance, transactions, today)
            }
        }
    }

    suspend fun create(card: CreditCard): String {
        val withId = card.copy(id = card.id.ifBlank { UUID.randomUUID().toString() })
        creditCardDao.upsert(withId.toEntity())
        return withId.id
    }

    suspend fun update(card: CreditCard) = creditCardDao.upsert(card.toEntity())

    suspend fun delete(id: String) {
        creditCardDao.findById(id)?.let { creditCardDao.delete(it) }
    }

    suspend fun upsertAll(cards: List<CreditCard>) =
        creditCardDao.upsertAll(cards.map { it.toEntity() })

    suspend fun deleteAll() = creditCardDao.deleteAll()

    suspend fun deleteDemoData() = creditCardDao.deleteDemoData()
}

// =============================================================================================
// Loans
// =============================================================================================

@Singleton
class LoanRepository @Inject constructor(
    private val loanDao: LoanDao,
    private val clock: KhaataClock,
) {

    fun observeOpen(): Flow<List<Loan>> =
        loanDao.observeOpen().map { list -> list.mapNotNull { it.toDomainOrNull() } }

    fun observeAll(): Flow<List<Loan>> =
        loanDao.observeAll().map { list -> list.mapNotNull { it.toDomainOrNull() } }

    suspend fun getAll(): List<Loan> = loanDao.getAll().mapNotNull { it.toDomainOrNull() }

    suspend fun findById(id: String): Loan? = loanDao.findById(id)?.toDomainOrNull()

    fun observeStatuses(): Flow<List<LoanStatus>> =
        observeOpen().map { loans -> loans.map { LoanCalculator.status(it, clock.today()) } }

    suspend fun statusFor(id: String): LoanStatus? =
        findById(id)?.let { LoanCalculator.status(it, clock.today()) }

    /** The full amortisation schedule, generated on demand rather than stored. */
    suspend fun scheduleFor(id: String): List<ai.labs32.khaata.core.calc.AmortisationEntry> =
        findById(id)?.let { LoanCalculator.schedule(it) }.orEmpty()

    /** Upcoming EMIs, so loan payments appear alongside other bills. */
    fun observeUpcomingEmis(days: Int = 30): Flow<List<ScheduledOccurrence>> {
        val today = clock.today()
        val horizon = today.plusDays(days.toLong())
        return observeOpen().map { loans ->
            loans.flatMap { loan ->
                LoanCalculator.schedule(loan)
                    .filter { !it.dueOn.isBefore(today) && !it.dueOn.isAfter(horizon) }
                    .map { entry ->
                        ScheduledOccurrence(
                            ruleId = loan.id,
                            name = loan.name,
                            type = TransactionType.EXPENSE,
                            amount = entry.payment,
                            dueOn = entry.dueOn,
                            accountId = loan.accountId.orEmpty(),
                            categoryId = loan.categoryId,
                            kind = OccurrenceKind.LOAN_EMI,
                        )
                    }
            }.sortedBy { it.dueOn }
        }
    }

    suspend fun create(loan: Loan): String {
        val withId = loan.copy(id = loan.id.ifBlank { UUID.randomUUID().toString() })
        loanDao.upsert(withId.toEntity())
        return withId.id
    }

    suspend fun update(loan: Loan) = loanDao.upsert(loan.toEntity())

    suspend fun delete(id: String) {
        loanDao.findById(id)?.let { loanDao.delete(it) }
    }

    suspend fun upsertAll(loans: List<Loan>) = loanDao.upsertAll(loans.map { it.toEntity() })

    suspend fun deleteAll() = loanDao.deleteAll()

    suspend fun deleteDemoData() = loanDao.deleteDemoData()
}

// =============================================================================================
// Investments
// =============================================================================================

@Singleton
class InvestmentRepository @Inject constructor(
    private val investmentDao: InvestmentDao,
    private val clock: KhaataClock,
) {

    fun observeOpen(): Flow<List<Investment>> =
        investmentDao.observeOpen().map { list -> list.mapNotNull { it.toDomainOrNull() } }

    fun observeAll(): Flow<List<Investment>> =
        investmentDao.observeAll().map { list -> list.mapNotNull { it.toDomainOrNull() } }

    suspend fun getAll(): List<Investment> =
        investmentDao.getAll().mapNotNull { it.toDomainOrNull() }

    suspend fun findById(id: String): Investment? = investmentDao.findById(id)?.toDomainOrNull()

    fun observePortfolio(currency: CurrencyCode = CurrencyCode.DEFAULT): Flow<PortfolioSummary> =
        observeOpen().map { InvestmentCalculator.portfolio(it, clock.today(), currency) }

    suspend fun create(investment: Investment): String {
        val withId = investment.copy(id = investment.id.ifBlank { UUID.randomUUID().toString() })
        investmentDao.upsert(withId.toEntity())
        return withId.id
    }

    suspend fun update(investment: Investment) = investmentDao.upsert(investment.toEntity())

    /** Records a fresh valuation, stamped with today so staleness can be shown honestly. */
    suspend fun updateValuation(id: String, currentValue: Money) {
        investmentDao.updateValuation(id, currentValue.minorUnits, clock.today())
    }

    suspend fun delete(id: String) {
        investmentDao.findById(id)?.let { investmentDao.delete(it) }
    }

    suspend fun upsertAll(investments: List<Investment>) =
        investmentDao.upsertAll(investments.map { it.toEntity() })

    suspend fun deleteAll() = investmentDao.deleteAll()

    suspend fun deleteDemoData() = investmentDao.deleteDemoData()
}

// =============================================================================================
// Goals
// =============================================================================================

@Singleton
class GoalRepository @Inject constructor(
    private val goalDao: GoalDao,
    private val clock: KhaataClock,
) {

    fun observeActive(): Flow<List<Goal>> =
        goalDao.observeActive().map { list -> list.mapNotNull { it.toDomainOrNull() } }

    fun observeAll(): Flow<List<Goal>> =
        goalDao.observeAll().map { list -> list.mapNotNull { it.toDomainOrNull() } }

    suspend fun getAll(): List<Goal> = goalDao.getAll().mapNotNull { it.toDomainOrNull() }

    suspend fun findById(id: String): Goal? = goalDao.findById(id)?.toDomainOrNull()

    fun observeProgress(): Flow<List<GoalProgress>> =
        observeActive().map { goals -> goals.map { GoalCalculator.progressOf(it, clock.today()) } }

    suspend fun progressFor(id: String): GoalProgress? =
        findById(id)?.let { GoalCalculator.progressOf(it, clock.today()) }

    suspend fun create(
        name: String,
        targetAmount: Money,
        currentAmount: Money,
        targetDate: LocalDate?,
        iconKey: String = "goal",
        colorSeed: Int = 0,
        notes: String? = null,
    ): String {
        val goal = Goal(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            targetAmount = targetAmount,
            currentAmount = currentAmount,
            targetDate = targetDate,
            startedOn = clock.today(),
            iconKey = iconKey,
            colorSeed = colorSeed,
            notes = notes,
        )
        goalDao.upsert(goal.toEntity())
        return goal.id
    }

    suspend fun update(goal: Goal) = goalDao.upsert(goal.toEntity())

    /**
     * Adds to a goal's balance and marks it achieved if that completes it.
     *
     * The achievement date is stamped once, the first time the target is reached, so a goal that
     * dips back below its target does not lose the fact that it was met.
     */
    suspend fun addProgress(id: String, amount: Money) {
        goalDao.addProgress(id, amount.minorUnits)
        val updated = findById(id) ?: return
        if (updated.isAchieved && updated.achievedOn == null) {
            goalDao.markAchieved(id, clock.today())
        }
    }

    suspend fun delete(id: String) {
        goalDao.findById(id)?.let { goalDao.delete(it) }
    }

    suspend fun upsertAll(goals: List<Goal>) = goalDao.upsertAll(goals.map { it.toEntity() })

    suspend fun deleteAll() = goalDao.deleteAll()

    suspend fun deleteDemoData() = goalDao.deleteDemoData()
}

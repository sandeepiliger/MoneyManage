package ai.labs32.khaata.core.testing

import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.model.Budget
import ai.labs32.khaata.core.model.BudgetPeriod
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.CategoryGroup
import ai.labs32.khaata.core.model.CategoryKind
import ai.labs32.khaata.core.model.Frequency
import ai.labs32.khaata.core.model.Goal
import ai.labs32.khaata.core.model.Investment
import ai.labs32.khaata.core.model.InvestmentKind
import ai.labs32.khaata.core.model.RecurringRule
import ai.labs32.khaata.core.model.Subscription
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import java.time.Instant
import java.time.LocalDate

/**
 * Concise builders for test data.
 *
 * Every builder supplies sensible defaults so a test names only the fields it is actually
 * asserting on — which keeps the interesting value visible instead of buried in boilerplate.
 */
object Fixtures {

    val TODAY: LocalDate = LocalDate.of(2026, 3, 15)
    val EPOCH: Instant = Instant.parse("2026-01-01T00:00:00Z")

    fun account(
        id: String = "acc-hdfc",
        name: String = "HDFC Bank",
        type: AccountType = AccountType.BANK,
        openingBalance: String = "0",
        currency: CurrencyCode = CurrencyCode.INR,
        includeInNetWorth: Boolean = true,
        includeInAvailableBalance: Boolean = type.countsAsSpendableByDefault,
        isArchived: Boolean = false,
    ): Account = Account(
        id = id,
        name = name,
        type = type,
        currency = currency,
        openingBalance = Money.of(openingBalance, currency),
        includeInNetWorth = includeInNetWorth,
        includeInAvailableBalance = includeInAvailableBalance,
        isArchived = isArchived,
        createdAt = EPOCH,
        updatedAt = EPOCH,
    )

    fun expense(
        id: String = "txn-${counter++}",
        amount: String,
        accountId: String = "acc-hdfc",
        categoryId: String? = "cat-food",
        merchant: String? = null,
        on: LocalDate = TODAY,
        isPending: Boolean = false,
        deletedAt: Instant? = null,
        tags: Set<String> = emptySet(),
    ): Transaction = Transaction(
        id = id,
        type = TransactionType.EXPENSE,
        amount = Money.of(amount),
        accountId = accountId,
        categoryId = categoryId,
        merchant = merchant,
        occurredOn = on,
        isPending = isPending,
        deletedAt = deletedAt,
        tags = tags,
        createdAt = EPOCH,
        updatedAt = EPOCH,
    )

    fun income(
        id: String = "txn-${counter++}",
        amount: String,
        accountId: String = "acc-hdfc",
        categoryId: String? = "cat-salary",
        merchant: String? = null,
        on: LocalDate = TODAY,
    ): Transaction = Transaction(
        id = id,
        type = TransactionType.INCOME,
        amount = Money.of(amount),
        accountId = accountId,
        categoryId = categoryId,
        merchant = merchant,
        occurredOn = on,
        createdAt = EPOCH,
        updatedAt = EPOCH,
    )

    fun transfer(
        id: String = "txn-${counter++}",
        amount: String,
        fromAccountId: String = "acc-hdfc",
        toAccountId: String = "acc-icici",
        on: LocalDate = TODAY,
    ): Transaction = Transaction(
        id = id,
        type = TransactionType.TRANSFER,
        amount = Money.of(amount),
        accountId = fromAccountId,
        transferAccountId = toAccountId,
        categoryId = null,
        occurredOn = on,
        createdAt = EPOCH,
        updatedAt = EPOCH,
    )

    fun category(
        id: String,
        name: String = id,
        group: CategoryGroup = CategoryGroup.FOOD,
        parentId: String? = null,
        kind: CategoryKind = CategoryKind.EXPENSE,
    ): Category = Category(
        id = id,
        name = name,
        group = group,
        parentId = parentId,
        kind = kind,
    )

    fun budget(
        id: String = "bud-food",
        name: String = "Food",
        limit: String = "10000",
        period: BudgetPeriod = BudgetPeriod.MONTHLY,
        categoryIds: Set<String> = setOf("cat-food"),
        accountIds: Set<String> = emptySet(),
        anchorDate: LocalDate = LocalDate.of(2026, 1, 1),
        alertThresholdPercent: Int = 85,
        rollsOver: Boolean = false,
    ): Budget = Budget(
        id = id,
        name = name,
        limit = Money.of(limit),
        period = period,
        categoryIds = categoryIds,
        accountIds = accountIds,
        anchorDate = anchorDate,
        alertThresholdPercent = alertThresholdPercent,
        rollsOver = rollsOver,
    )

    fun recurring(
        id: String = "rec-rent",
        name: String = "Rent",
        amount: String = "25000",
        type: TransactionType = TransactionType.EXPENSE,
        frequency: Frequency = Frequency.MONTHLY,
        interval: Int = 1,
        startDate: LocalDate = LocalDate.of(2026, 1, 5),
        endDate: LocalDate? = null,
        maxOccurrences: Int? = null,
        lastPostedOn: LocalDate? = null,
        accountId: String = "acc-hdfc",
        isActive: Boolean = true,
    ): RecurringRule = RecurringRule(
        id = id,
        name = name,
        type = type,
        amount = Money.of(amount),
        accountId = accountId,
        categoryId = "cat-rent",
        frequency = frequency,
        interval = interval,
        startDate = startDate,
        endDate = endDate,
        maxOccurrences = maxOccurrences,
        lastPostedOn = lastPostedOn,
        isActive = isActive,
    )

    fun subscription(
        id: String = "sub-netflix",
        name: String = "Netflix",
        amount: String = "649",
        cycle: Frequency = Frequency.MONTHLY,
        nextPaymentDate: LocalDate = LocalDate.of(2026, 3, 20),
        startedOn: LocalDate = LocalDate.of(2025, 3, 20),
        isActive: Boolean = true,
        cancelledOn: LocalDate? = null,
    ): Subscription = Subscription(
        id = id,
        name = name,
        amount = Money.of(amount),
        cycle = cycle,
        nextPaymentDate = nextPaymentDate,
        startedOn = startedOn,
        isActive = isActive,
        cancelledOn = cancelledOn,
    )

    fun goal(
        id: String = "goal-emergency",
        name: String = "Emergency Fund",
        target: String = "300000",
        current: String = "120000",
        targetDate: LocalDate? = LocalDate.of(2026, 12, 31),
        startedOn: LocalDate = LocalDate.of(2026, 1, 1),
    ): Goal = Goal(
        id = id,
        name = name,
        targetAmount = Money.of(target),
        currentAmount = Money.of(current),
        targetDate = targetDate,
        startedOn = startedOn,
    )

    fun investment(
        id: String = "inv-nifty",
        name: String = "Index Fund",
        kind: InvestmentKind = InvestmentKind.MUTUAL_FUND,
        invested: String = "100000",
        currentValue: String = "118000",
        startedOn: LocalDate = LocalDate.of(2024, 3, 15),
        valuedOn: LocalDate = TODAY,
    ): Investment = Investment(
        id = id,
        name = name,
        kind = kind,
        investedAmount = Money.of(invested),
        currentValue = Money.of(currentValue),
        startedOn = startedOn,
        valuedOn = valuedOn,
    )

    private var counter = 0
}

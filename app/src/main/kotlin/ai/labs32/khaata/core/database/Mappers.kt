package ai.labs32.khaata.core.database

import ai.labs32.khaata.core.categorize.MerchantNormaliser
import ai.labs32.khaata.core.database.entity.AccountEntity
import ai.labs32.khaata.core.database.entity.BudgetEntity
import ai.labs32.khaata.core.database.entity.CategoryEntity
import ai.labs32.khaata.core.database.entity.CreditCardEntity
import ai.labs32.khaata.core.database.entity.GoalEntity
import ai.labs32.khaata.core.database.entity.InvestmentEntity
import ai.labs32.khaata.core.database.entity.LoanEntity
import ai.labs32.khaata.core.database.entity.MerchantRuleEntity
import ai.labs32.khaata.core.database.entity.MoneyColumns
import ai.labs32.khaata.core.database.entity.ReceiptEntity
import ai.labs32.khaata.core.database.entity.RecurringRuleEntity
import ai.labs32.khaata.core.database.entity.SubscriptionEntity
import ai.labs32.khaata.core.database.entity.TagEntity
import ai.labs32.khaata.core.database.entity.TransactionEntity
import ai.labs32.khaata.core.database.entity.UserProfileEntity
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.Budget
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.CreditCard
import ai.labs32.khaata.core.model.Goal
import ai.labs32.khaata.core.model.Investment
import ai.labs32.khaata.core.model.Loan
import ai.labs32.khaata.core.model.MerchantRule
import ai.labs32.khaata.core.model.Receipt
import ai.labs32.khaata.core.model.RecurringRule
import ai.labs32.khaata.core.model.Subscription
import ai.labs32.khaata.core.model.Tag
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.UserProfile
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money

/**
 * Conversion between persistence entities and domain models.
 *
 * The two are kept separate on purpose. Domain models validate their own invariants in `init` and
 * carry behaviour; entities are flat, annotated rows shaped for SQLite. Letting Room annotate the
 * domain types directly would drag storage concerns into the layer that is meant to be free of
 * them, and would mean a schema change rippling into every calculator.
 *
 * Entity → domain conversion is defensive. A row that violates a domain invariant (a zero amount,
 * a transfer with no destination) returns null rather than throwing, so one bad row degrades one
 * list item instead of crashing a screen.
 */

// ---- Accounts ---------------------------------------------------------------------------------

fun AccountEntity.toDomain(): Account = Account(
    id = id,
    name = name,
    type = type,
    currency = CurrencyCode.fromCodeOrDefault(currency),
    openingBalance = openingBalance.toMoney(),
    institution = institution,
    maskedIdentifier = maskedIdentifier,
    includeInNetWorth = includeInNetWorth,
    includeInAvailableBalance = includeInAvailableBalance,
    colorSeed = colorSeed,
    iconKey = iconKey,
    notes = notes,
    sortOrder = sortOrder,
    isArchived = isArchived,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Account.toEntity(): AccountEntity = AccountEntity(
    id = id,
    name = name,
    type = type,
    currency = currency.code,
    openingBalance = MoneyColumns.from(openingBalance),
    institution = institution,
    maskedIdentifier = maskedIdentifier,
    includeInNetWorth = includeInNetWorth,
    includeInAvailableBalance = includeInAvailableBalance,
    colorSeed = colorSeed,
    iconKey = iconKey,
    notes = notes,
    sortOrder = sortOrder,
    isArchived = isArchived,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// ---- Categories -------------------------------------------------------------------------------

fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    name = name,
    group = group,
    parentId = parentId,
    kind = kind,
    iconKey = iconKey,
    colorSeed = colorSeed,
    isSystem = isSystem,
    isArchived = isArchived,
    sortOrder = sortOrder,
)

fun Category.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    name = name,
    group = group,
    parentId = parentId,
    kind = kind,
    iconKey = iconKey,
    colorSeed = colorSeed,
    isSystem = isSystem,
    isArchived = isArchived,
    sortOrder = sortOrder,
)

// ---- Transactions -----------------------------------------------------------------------------

/**
 * Converts a stored row to a domain transaction.
 *
 * Returns null when the row cannot satisfy the domain's invariants — an amount of zero, or a
 * transfer whose destination account has gone. That is a real possibility after a partial import
 * or a hand-edited backup, and dropping one row from a list is a far better outcome than an
 * exception that takes the screen down with it.
 */
fun TransactionEntity.toDomainOrNull(): Transaction? = runCatching {
    Transaction(
        id = id,
        type = type,
        amount = amount.toMoney(),
        accountId = accountId,
        transferAccountId = transferAccountId,
        categoryId = categoryId,
        merchant = merchant,
        note = note,
        occurredOn = occurredOn,
        tags = tags,
        receiptId = receiptId,
        source = source,
        referenceNumber = referenceNumber,
        recurringRuleId = recurringRuleId,
        isPending = isPending,
        deletedAt = deletedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}.getOrNull()

/** Converts a list of rows, silently dropping any that are unusable. */
fun List<TransactionEntity>.toDomain(): List<Transaction> = mapNotNull { it.toDomainOrNull() }

fun Transaction.toEntity(): TransactionEntity = TransactionEntity(
    id = id,
    type = type,
    amount = MoneyColumns.from(amount),
    accountId = accountId,
    transferAccountId = transferAccountId,
    categoryId = categoryId,
    merchant = merchant,
    // Denormalised at write time so merchant search and rule lookup never recompute it.
    merchantKey = MerchantNormaliser.normalise(merchant),
    note = note,
    occurredOn = occurredOn,
    tags = tags,
    receiptId = receiptId,
    source = source,
    referenceNumber = referenceNumber,
    recurringRuleId = recurringRuleId,
    isPending = isPending,
    deletedAt = deletedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// ---- Budgets ----------------------------------------------------------------------------------

fun BudgetEntity.toDomainOrNull(): Budget? = runCatching {
    Budget(
        id = id,
        name = name,
        limit = limitAmount.toMoney(),
        period = period,
        categoryIds = categoryIds.toSet(),
        accountIds = accountIds.toSet(),
        anchorDate = anchorDate,
        endDate = endDate,
        alertThresholdPercent = alertThresholdPercent,
        rollsOver = rollsOver,
        isActive = isActive,
        sortOrder = sortOrder,
    )
}.getOrNull()

fun Budget.toEntity(): BudgetEntity = BudgetEntity(
    id = id,
    name = name,
    limitAmount = MoneyColumns.from(limit),
    period = period,
    categoryIds = categoryIds.toList(),
    accountIds = accountIds.toList(),
    anchorDate = anchorDate,
    endDate = endDate,
    alertThresholdPercent = alertThresholdPercent,
    rollsOver = rollsOver,
    isActive = isActive,
    sortOrder = sortOrder,
)

// ---- Recurring --------------------------------------------------------------------------------

fun RecurringRuleEntity.toDomainOrNull(): RecurringRule? = runCatching {
    RecurringRule(
        id = id,
        name = name,
        type = type,
        amount = amount.toMoney(),
        accountId = accountId,
        transferAccountId = transferAccountId,
        categoryId = categoryId,
        merchant = merchant,
        note = note,
        frequency = frequency,
        interval = interval,
        startDate = startDate,
        endDate = endDate,
        maxOccurrences = maxOccurrences,
        lastPostedOn = lastPostedOn,
        autoPost = autoPost,
        reminderDaysBefore = reminderDaysBefore,
        isActive = isActive,
    )
}.getOrNull()

fun RecurringRule.toEntity(): RecurringRuleEntity = RecurringRuleEntity(
    id = id,
    name = name,
    type = type,
    amount = MoneyColumns.from(amount),
    accountId = accountId,
    transferAccountId = transferAccountId,
    categoryId = categoryId,
    merchant = merchant,
    note = note,
    frequency = frequency,
    interval = interval,
    startDate = startDate,
    endDate = endDate,
    maxOccurrences = maxOccurrences,
    lastPostedOn = lastPostedOn,
    autoPost = autoPost,
    reminderDaysBefore = reminderDaysBefore,
    isActive = isActive,
)

// ---- Subscriptions ----------------------------------------------------------------------------

fun SubscriptionEntity.toDomainOrNull(): Subscription? = runCatching {
    Subscription(
        id = id,
        name = name,
        amount = amount.toMoney(),
        cycle = cycle,
        nextPaymentDate = nextPaymentDate,
        startedOn = startedOn,
        cancelledOn = cancelledOn,
        categoryId = categoryId,
        accountId = accountId,
        merchantKey = merchantKey,
        reminderDaysBefore = reminderDaysBefore,
        iconKey = iconKey,
        colorSeed = colorSeed,
        notes = notes,
        isActive = isActive,
    )
}.getOrNull()

fun Subscription.toEntity(): SubscriptionEntity = SubscriptionEntity(
    id = id,
    name = name,
    amount = MoneyColumns.from(amount),
    cycle = cycle,
    nextPaymentDate = nextPaymentDate,
    startedOn = startedOn,
    cancelledOn = cancelledOn,
    categoryId = categoryId,
    accountId = accountId,
    merchantKey = merchantKey ?: MerchantNormaliser.normalise(name),
    reminderDaysBefore = reminderDaysBefore,
    iconKey = iconKey,
    colorSeed = colorSeed,
    notes = notes,
    isActive = isActive,
)

// ---- Credit cards -----------------------------------------------------------------------------

fun CreditCardEntity.toDomainOrNull(): CreditCard? = runCatching {
    CreditCard(
        id = id,
        accountId = accountId,
        cardName = cardName,
        issuer = issuer,
        creditLimit = creditLimit.toMoney(),
        statementDayOfMonth = statementDayOfMonth,
        dueDayOfMonth = dueDayOfMonth,
        minimumDuePercent = minimumDuePercent,
        minimumDueFloor = minimumDueFloor.toMoney(),
        lastFourDigits = lastFourDigits,
        colorSeed = colorSeed,
        isActive = isActive,
    )
}.getOrNull()

fun CreditCard.toEntity(): CreditCardEntity = CreditCardEntity(
    id = id,
    accountId = accountId,
    cardName = cardName,
    issuer = issuer,
    creditLimit = MoneyColumns.from(creditLimit),
    statementDayOfMonth = statementDayOfMonth,
    dueDayOfMonth = dueDayOfMonth,
    minimumDuePercent = minimumDuePercent,
    minimumDueFloor = MoneyColumns.from(minimumDueFloor),
    lastFourDigits = lastFourDigits,
    colorSeed = colorSeed,
    isActive = isActive,
)

// ---- Loans ------------------------------------------------------------------------------------

fun LoanEntity.toDomainOrNull(): Loan? = runCatching {
    val currency = CurrencyCode.fromCodeOrDefault(principal.currency)
    Loan(
        id = id,
        name = name,
        lender = lender,
        principal = principal.toMoney(),
        annualInterestRatePercent = annualInterestRatePercent,
        tenureMonths = tenureMonths,
        startDate = startDate,
        emiOverride = emiOverrideMinor?.let { Money.ofMinor(it, currency) },
        emiDayOfMonth = emiDayOfMonth,
        accountId = accountId,
        categoryId = categoryId,
        colorSeed = colorSeed,
        isClosed = isClosed,
    )
}.getOrNull()

fun Loan.toEntity(): LoanEntity = LoanEntity(
    id = id,
    name = name,
    lender = lender,
    principal = MoneyColumns.from(principal),
    annualInterestRatePercent = annualInterestRatePercent,
    tenureMonths = tenureMonths,
    startDate = startDate,
    emiOverrideMinor = emiOverride?.minorUnits,
    emiDayOfMonth = emiDayOfMonth,
    accountId = accountId,
    categoryId = categoryId,
    colorSeed = colorSeed,
    isClosed = isClosed,
)

// ---- Investments ------------------------------------------------------------------------------

fun InvestmentEntity.toDomainOrNull(): Investment? = runCatching {
    Investment(
        id = id,
        name = name,
        kind = kind,
        investedAmount = investedAmount.toMoney(),
        currentValue = currentValue.toMoney(),
        startedOn = startedOn,
        valuedOn = valuedOn,
        accountId = accountId,
        units = units,
        folioOrSymbol = folioOrSymbol,
        notes = notes,
        colorSeed = colorSeed,
        isClosed = isClosed,
    )
}.getOrNull()

fun Investment.toEntity(): InvestmentEntity = InvestmentEntity(
    id = id,
    name = name,
    kind = kind,
    investedAmount = MoneyColumns.from(investedAmount),
    currentValue = MoneyColumns.from(currentValue),
    startedOn = startedOn,
    valuedOn = valuedOn,
    accountId = accountId,
    units = units,
    folioOrSymbol = folioOrSymbol,
    notes = notes,
    colorSeed = colorSeed,
    isClosed = isClosed,
)

// ---- Goals ------------------------------------------------------------------------------------

fun GoalEntity.toDomainOrNull(): Goal? = runCatching {
    Goal(
        id = id,
        name = name,
        targetAmount = targetAmount.toMoney(),
        currentAmount = currentAmount.toMoney(),
        targetDate = targetDate,
        startedOn = startedOn,
        achievedOn = achievedOn,
        accountId = accountId,
        iconKey = iconKey,
        colorSeed = colorSeed,
        notes = notes,
        isArchived = isArchived,
    )
}.getOrNull()

fun Goal.toEntity(): GoalEntity = GoalEntity(
    id = id,
    name = name,
    targetAmount = MoneyColumns.from(targetAmount),
    currentAmount = MoneyColumns.from(currentAmount),
    targetDate = targetDate,
    startedOn = startedOn,
    achievedOn = achievedOn,
    accountId = accountId,
    iconKey = iconKey,
    colorSeed = colorSeed,
    notes = notes,
    isArchived = isArchived,
)

// ---- Supporting -------------------------------------------------------------------------------

fun TagEntity.toDomain(): Tag = Tag(id = id, name = name, colorSeed = colorSeed, usageCount = usageCount)

fun Tag.toEntity(): TagEntity = TagEntity(id = id, name = name, colorSeed = colorSeed, usageCount = usageCount)

fun ReceiptEntity.toDomain(): Receipt = Receipt(
    id = id,
    transactionId = transactionId,
    relativePath = relativePath,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    capturedOn = capturedOn,
)

fun Receipt.toEntity(): ReceiptEntity = ReceiptEntity(
    id = id,
    transactionId = transactionId,
    relativePath = relativePath,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    capturedOn = capturedOn,
)

fun MerchantRuleEntity.toDomain(): MerchantRule = MerchantRule(
    id = id,
    merchantKey = merchantKey,
    categoryId = categoryId,
    accountId = accountId,
    confidence = confidence,
    isUserDefined = isUserDefined,
    isSeeded = isSeeded,
)

fun MerchantRule.toEntity(): MerchantRuleEntity = MerchantRuleEntity(
    id = id,
    merchantKey = merchantKey,
    categoryId = categoryId,
    accountId = accountId,
    confidence = confidence,
    isUserDefined = isUserDefined,
    isSeeded = isSeeded,
)

fun UserProfileEntity.toDomainOrNull(): UserProfile? = runCatching {
    val currencyCode = CurrencyCode.fromCodeOrDefault(currency)
    UserProfile(
        id = id,
        displayName = displayName,
        currency = currencyCode,
        languageTag = languageTag,
        monthlyIncome = monthlyIncomeMinor?.let { Money.ofMinor(it, currencyCode) },
        monthStartDay = monthStartDay,
        hasCompletedOnboarding = hasCompletedOnboarding,
        isDemoMode = isDemoMode,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}.getOrNull()

fun UserProfile.toEntity(): UserProfileEntity = UserProfileEntity(
    id = id,
    displayName = displayName,
    currency = currency.code,
    languageTag = languageTag,
    monthlyIncomeMinor = monthlyIncome?.minorUnits,
    monthStartDay = monthStartDay,
    hasCompletedOnboarding = hasCompletedOnboarding,
    isDemoMode = isDemoMode,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

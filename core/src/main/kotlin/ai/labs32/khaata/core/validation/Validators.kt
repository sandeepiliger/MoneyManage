package ai.labs32.khaata.core.validation

import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyParser
import java.time.LocalDate

/**
 * Form-level validation.
 *
 * Distinct from the `require` checks inside the domain models: those enforce invariants that must
 * never be violated and throw when they are. These run against half-typed input while the user is
 * still filling a form, so they return messages instead.
 *
 * Messages here are English defaults. The Android layer maps [ValidationError.code] onto a
 * localised string, so Hindi users see Hindi and the codes stay stable for tests.
 */
sealed interface ValidationResult<out T> {
    data class Valid<T>(val value: T) : ValidationResult<T>
    data class Invalid(val errors: List<ValidationError>) : ValidationResult<Nothing> {
        constructor(error: ValidationError) : this(listOf(error))
    }

    val isValid: Boolean get() = this is Valid

    fun errorsOrEmpty(): List<ValidationError> = when (this) {
        is Invalid -> errors
        is Valid -> emptyList()
    }

    fun valueOrNull(): T? = (this as? Valid)?.value
}

/** A single validation failure, identified by a stable [code] for localisation and tests. */
data class ValidationError(
    val field: String,
    val code: String,
    val message: String,
)

/** Validates a transaction being created or edited. */
object TransactionValidator {

    /**
     * Validates the amount a user typed.
     *
     * Rejects zero explicitly: a zero-rupee transaction is always a mistake — usually a tap on
     * save before typing — and silently accepting it leaves a confusing empty row in the ledger.
     */
    fun validateAmount(
        raw: String?,
        currency: CurrencyCode = CurrencyCode.DEFAULT,
    ): ValidationResult<Money> {
        if (raw.isNullOrBlank()) {
            return ValidationResult.Invalid(
                ValidationError("amount", "amount_required", "Enter an amount"),
            )
        }
        val parsed = MoneyParser.parse(raw, currency)
            ?: return ValidationResult.Invalid(
                ValidationError("amount", "amount_invalid", "That is not a valid amount"),
            )
        if (parsed.isZero) {
            return ValidationResult.Invalid(
                ValidationError("amount", "amount_zero", "Amount must be more than zero"),
            )
        }
        return ValidationResult.Valid(parsed)
    }

    /**
     * Validates the whole transaction form.
     *
     * Returns every problem at once rather than the first one, so the user fixes the form in one
     * pass instead of playing whack-a-mole with one error at a time.
     */
    fun validate(input: TransactionInput, today: LocalDate): ValidationResult<TransactionInput> {
        val errors = buildList {
            when (val amount = validateAmount(input.amountText, input.currency)) {
                is ValidationResult.Invalid -> addAll(amount.errors)
                is ValidationResult.Valid -> Unit
            }

            if (input.accountId.isBlank()) {
                add(ValidationError("account", "account_required", "Choose an account"))
            }

            if (input.type == TransactionType.TRANSFER) {
                when {
                    input.transferAccountId.isNullOrBlank() ->
                        add(ValidationError("transferAccount", "transfer_account_required", "Choose where the money is going"))
                    input.transferAccountId == input.accountId ->
                        add(ValidationError("transferAccount", "transfer_same_account", "Choose a different destination account"))
                }
            } else if (input.categoryId.isNullOrBlank()) {
                // Transfers have no category by design; everything else needs one for reports to
                // mean anything.
                add(ValidationError("category", "category_required", "Choose a category"))
            }

            if (input.occurredOn.isAfter(today.plusDays(MAX_FUTURE_DAYS))) {
                add(
                    ValidationError(
                        "date",
                        "date_too_far_future",
                        "That date is more than a year away",
                    ),
                )
            }
            if (input.occurredOn.isBefore(EARLIEST_DATE)) {
                add(ValidationError("date", "date_too_far_past", "That date is too far in the past"))
            }
            if (input.note != null && input.note.length > MAX_NOTE_LENGTH) {
                add(ValidationError("note", "note_too_long", "Note is too long"))
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid(input) else ValidationResult.Invalid(errors)
    }

    /**
     * Warns when an expense would take a non-credit account below zero.
     *
     * A warning, not an error: cash accounts genuinely drift out of sync with reality, and
     * blocking the entry would just teach the user to stop recording things.
     */
    fun overdraftWarning(
        account: Account,
        currentBalance: Money,
        amount: Money,
        type: TransactionType,
    ): String? {
        if (account.isLiability) return null
        if (type == TransactionType.INCOME) return null
        val after = currentBalance - amount
        return if (after.isNegative) "This will take ${account.name} below zero" else null
    }

    private const val MAX_FUTURE_DAYS = 365L
    private const val MAX_NOTE_LENGTH = 500
    private val EARLIEST_DATE = LocalDate.of(1970, 1, 1)
}

/** The transaction form's state, as the user has filled it in so far. */
data class TransactionInput(
    val type: TransactionType,
    /** Raw text as typed, so validation can explain what is wrong with it. */
    val amountText: String?,
    val currency: CurrencyCode,
    val accountId: String,
    val transferAccountId: String? = null,
    val categoryId: String? = null,
    val merchant: String? = null,
    val note: String? = null,
    val occurredOn: LocalDate,
    val tags: Set<String> = emptySet(),
)

/** Validates an account being created or edited. */
object AccountValidator {

    fun validate(
        name: String?,
        openingBalanceText: String?,
        currency: CurrencyCode,
        existingNames: Set<String>,
        isEditingExisting: Boolean,
    ): ValidationResult<Unit> {
        val errors = buildList {
            val trimmed = name?.trim()
            when {
                trimmed.isNullOrBlank() ->
                    add(ValidationError("name", "name_required", "Give the account a name"))
                trimmed.length > MAX_NAME_LENGTH ->
                    add(ValidationError("name", "name_too_long", "That name is too long"))
                !isEditingExisting && existingNames.any { it.equals(trimmed, ignoreCase = true) } ->
                    add(ValidationError("name", "name_duplicate", "You already have an account with that name"))
            }

            // An opening balance may legitimately be negative (a card already carrying a
            // balance), so only unparseable text is rejected here.
            if (!openingBalanceText.isNullOrBlank()) {
                val cleaned = openingBalanceText.trim().removePrefix("-")
                if (MoneyParser.parse(cleaned, currency) == null) {
                    add(ValidationError("openingBalance", "balance_invalid", "That is not a valid amount"))
                }
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid(Unit) else ValidationResult.Invalid(errors)
    }

    private const val MAX_NAME_LENGTH = 60
}

/** Validates a budget being created or edited. */
object BudgetValidator {

    fun validate(
        name: String?,
        limitText: String?,
        currency: CurrencyCode,
        alertThresholdPercent: Int,
    ): ValidationResult<Unit> {
        val errors = buildList {
            if (name.isNullOrBlank()) {
                add(ValidationError("name", "name_required", "Give the budget a name"))
            }
            val limit = MoneyParser.parse(limitText, currency)
            when {
                limitText.isNullOrBlank() ->
                    add(ValidationError("limit", "limit_required", "Enter a budget amount"))
                limit == null ->
                    add(ValidationError("limit", "limit_invalid", "That is not a valid amount"))
                limit.isZero ->
                    add(ValidationError("limit", "limit_zero", "Budget must be more than zero"))
            }
            if (alertThresholdPercent !in 1..100) {
                add(ValidationError("alert", "alert_out_of_range", "Alert must be between 1% and 100%"))
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid(Unit) else ValidationResult.Invalid(errors)
    }
}

/** Validates a category being created or edited. */
object CategoryValidator {

    /**
     * Validates a category name and its place in the tree.
     *
     * [siblingNames] is deliberately scoped to siblings rather than to every category: "Insurance"
     * under Health and "Insurance" under Financial are two different things people genuinely track
     * separately, and rejecting the second because the first exists would be wrong. Two children of
     * the same parent sharing a name, on the other hand, is always a mistake — the picker would
     * show the same word twice with no way to tell them apart.
     *
     * The two-level limit is enforced here rather than only in the UI, because a subcategory whose
     * parent is itself a subcategory would break every roll-up in reports and budgets.
     */
    fun validate(
        name: String?,
        siblingNames: Set<String>,
        parentIsSubcategory: Boolean,
        hasChildren: Boolean,
        isBecomingSubcategory: Boolean,
    ): ValidationResult<Unit> {
        val errors = buildList {
            val trimmed = name?.trim()
            when {
                trimmed.isNullOrBlank() ->
                    add(ValidationError("name", "name_required", "Give the category a name"))
                trimmed.length > MAX_NAME_LENGTH ->
                    add(ValidationError("name", "name_too_long", "That name is too long"))
                siblingNames.any { it.equals(trimmed, ignoreCase = true) } ->
                    add(ValidationError("name", "name_duplicate", "There is already a category with that name here"))
            }

            if (parentIsSubcategory) {
                add(ValidationError("parent", "parent_too_deep", "Categories only go two levels deep"))
            }

            // Moving a parent that has children under another parent would orphan them into a
            // third level, so it is refused while the children exist.
            if (hasChildren && isBecomingSubcategory) {
                add(
                    ValidationError(
                        "parent",
                        "parent_has_children",
                        "Move or delete this category's subcategories first",
                    ),
                )
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid(Unit) else ValidationResult.Invalid(errors)
    }

    private const val MAX_NAME_LENGTH = 40
}

/** Validates a goal being created or edited. */
object GoalValidator {

    fun validate(
        name: String?,
        targetText: String?,
        currentText: String?,
        currency: CurrencyCode,
        targetDate: LocalDate?,
        today: LocalDate,
    ): ValidationResult<Unit> {
        val errors = buildList {
            if (name.isNullOrBlank()) {
                add(ValidationError("name", "name_required", "Give the goal a name"))
            }
            val target = MoneyParser.parse(targetText, currency)
            when {
                targetText.isNullOrBlank() ->
                    add(ValidationError("target", "target_required", "Enter a target amount"))
                target == null ->
                    add(ValidationError("target", "target_invalid", "That is not a valid amount"))
                target.isZero ->
                    add(ValidationError("target", "target_zero", "Target must be more than zero"))
            }
            if (!currentText.isNullOrBlank()) {
                val current = MoneyParser.parse(currentText, currency)
                if (current == null) {
                    add(ValidationError("current", "current_invalid", "That is not a valid amount"))
                } else if (target != null && current > target) {
                    add(ValidationError("current", "current_exceeds_target", "Saved amount is more than the target"))
                }
            }
            if (targetDate != null && targetDate.isBefore(today)) {
                add(ValidationError("targetDate", "date_in_past", "Choose a date in the future"))
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid(Unit) else ValidationResult.Invalid(errors)
    }
}

/**
 * A holding, before it becomes an [ai.labs32.khaata.core.model.Investment].
 *
 * The model's own `init` block rejects a negative amount or a valuation dated before the start,
 * by throwing. That is the right last line of defence and the wrong thing to show a user, so the
 * same rules are checked here first and come back as messages a field can display.
 */
object InvestmentValidator {

    fun validate(
        name: String?,
        investedText: String?,
        currentValueText: String?,
        unitsText: String?,
        currency: CurrencyCode,
        startedOn: LocalDate,
        valuedOn: LocalDate,
        today: LocalDate,
    ): ValidationResult<Unit> {
        val errors = buildList {
            if (name.isNullOrBlank()) {
                add(ValidationError("name", "name_required", "Give the investment a name"))
            }

            val invested = MoneyParser.parse(investedText, currency)
            when {
                investedText.isNullOrBlank() ->
                    add(ValidationError("invested", "invested_required", "Enter the amount invested"))
                invested == null ->
                    add(ValidationError("invested", "invested_invalid", "That is not a valid amount"))
                invested.isNegative ->
                    add(ValidationError("invested", "invested_negative", "Amount cannot be negative"))
            }

            // Required rather than optional: a holding with no current value cannot show a gain,
            // which is the only reason to track it here. The editor prefills it with the invested
            // amount on a new holding, so this is one tap for someone who has not valued it yet.
            val currentValue = MoneyParser.parse(currentValueText, currency)
            when {
                currentValueText.isNullOrBlank() ->
                    add(ValidationError("currentValue", "value_required", "Enter what it is worth now"))
                currentValue == null ->
                    add(ValidationError("currentValue", "value_invalid", "That is not a valid amount"))
                currentValue.isNegative ->
                    add(ValidationError("currentValue", "value_negative", "Value cannot be negative"))
            }

            if (!unitsText.isNullOrBlank() && unitsText.toBigDecimalOrNull() == null) {
                add(ValidationError("units", "units_invalid", "That is not a valid number of units"))
            }

            // A holding cannot start in the future, and cannot be valued before it existed. The
            // second is what the model throws on, so catching it here is the difference between a
            // field error and a crash.
            if (startedOn.isAfter(today)) {
                add(ValidationError("startedOn", "start_in_future", "Choose a date in the past"))
            }
            if (valuedOn.isBefore(startedOn)) {
                add(ValidationError("valuedOn", "valued_before_start", "Valuation cannot predate the start"))
            }
            if (valuedOn.isAfter(today)) {
                add(ValidationError("valuedOn", "valued_in_future", "Choose a date in the past"))
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid(Unit) else ValidationResult.Invalid(errors)
    }
}

/**
 * A loan, before it becomes an [ai.labs32.khaata.core.model.Loan].
 *
 * Every rule here mirrors one of that model's `init` requirements. The model throws, which is the
 * right last line of defence and the wrong thing to show someone filling in a form, so a gap
 * between the two is a crash on save rather than a message under a field.
 */
object LoanValidator {

    /** Above this, an interest rate is a data entry error rather than a loan. */
    private val MAX_RATE = java.math.BigDecimal("100")

    fun validate(
        name: String?,
        principalText: String?,
        ratePercentText: String?,
        tenureMonthsText: String?,
        emiDayText: String?,
        emiOverrideText: String?,
        currency: CurrencyCode,
    ): ValidationResult<Unit> {
        val errors = buildList {
            if (name.isNullOrBlank()) {
                add(ValidationError("name", "name_required", "Give the loan a name"))
            }

            val principal = MoneyParser.parse(principalText, currency)
            when {
                principalText.isNullOrBlank() ->
                    add(ValidationError("principal", "principal_required", "Enter the loan amount"))
                principal == null ->
                    add(ValidationError("principal", "principal_invalid", "That is not a valid amount"))
                !principal.isPositive ->
                    add(ValidationError("principal", "principal_not_positive", "Loan amount must be more than zero"))
            }

            val rate = ratePercentText?.trim()?.toBigDecimalOrNull()
            when {
                ratePercentText.isNullOrBlank() ->
                    add(ValidationError("rate", "rate_required", "Enter the interest rate"))
                rate == null ->
                    add(ValidationError("rate", "rate_invalid", "That is not a valid rate"))
                rate.signum() < 0 ->
                    add(ValidationError("rate", "rate_negative", "Interest rate cannot be negative"))
                rate >= MAX_RATE ->
                    add(ValidationError("rate", "rate_too_high", "A rate of 100% or more is almost certainly a typo"))
            }

            val tenure = tenureMonthsText?.trim()?.toIntOrNull()
            when {
                tenureMonthsText.isNullOrBlank() ->
                    add(ValidationError("tenure", "tenure_required", "Enter the tenure in months"))
                tenure == null ->
                    add(ValidationError("tenure", "tenure_invalid", "Enter a whole number of months"))
                tenure !in 1..600 ->
                    add(ValidationError("tenure", "tenure_out_of_range", "Tenure must be between 1 and 600 months"))
            }

            val emiDay = emiDayText?.trim()?.toIntOrNull()
            when {
                emiDayText.isNullOrBlank() ->
                    add(ValidationError("emiDay", "emi_day_required", "Enter the EMI day"))
                emiDay == null || emiDay !in 1..31 ->
                    add(ValidationError("emiDay", "emi_day_out_of_range", "EMI day must be between 1 and 31"))
            }

            // Optional: the lender's own EMI figure, when it differs from the computed one by a
            // rupee or two. Blank means "use ours".
            if (!emiOverrideText.isNullOrBlank()) {
                val emi = MoneyParser.parse(emiOverrideText, currency)
                if (emi == null) {
                    add(ValidationError("emiOverride", "emi_invalid", "That is not a valid amount"))
                } else if (!emi.isPositive) {
                    add(ValidationError("emiOverride", "emi_not_positive", "EMI must be more than zero"))
                }
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid(Unit) else ValidationResult.Invalid(errors)
    }
}

/**
 * A credit card, before it becomes an [ai.labs32.khaata.core.model.CreditCard].
 *
 * As with [LoanValidator], these mirror the model's own `init` requirements so a bad entry is a
 * field error rather than an exception.
 */
object CreditCardValidator {

    private val HUNDRED = java.math.BigDecimal("100")

    fun validate(
        cardName: String?,
        issuer: String?,
        creditLimitText: String?,
        statementDayText: String?,
        dueDayText: String?,
        minimumDuePercentText: String?,
        lastFourDigits: String?,
        currency: CurrencyCode,
    ): ValidationResult<Unit> {
        val errors = buildList {
            if (cardName.isNullOrBlank()) {
                add(ValidationError("cardName", "name_required", "Give the card a name"))
            }
            if (issuer.isNullOrBlank()) {
                add(ValidationError("issuer", "issuer_required", "Enter the issuing bank"))
            }

            val limit = MoneyParser.parse(creditLimitText, currency)
            when {
                creditLimitText.isNullOrBlank() ->
                    add(ValidationError("creditLimit", "limit_required", "Enter the credit limit"))
                limit == null ->
                    add(ValidationError("creditLimit", "limit_invalid", "That is not a valid amount"))
                !limit.isPositive ->
                    add(ValidationError("creditLimit", "limit_not_positive", "Credit limit must be more than zero"))
            }

            val statementDay = statementDayText?.trim()?.toIntOrNull()
            when {
                statementDayText.isNullOrBlank() ->
                    add(ValidationError("statementDay", "statement_day_required", "Enter the statement day"))
                statementDay == null || statementDay !in 1..31 ->
                    add(ValidationError("statementDay", "statement_day_out_of_range", "Statement day must be between 1 and 31"))
            }

            val dueDay = dueDayText?.trim()?.toIntOrNull()
            when {
                dueDayText.isNullOrBlank() ->
                    add(ValidationError("dueDay", "due_day_required", "Enter the payment due day"))
                dueDay == null || dueDay !in 1..31 ->
                    add(ValidationError("dueDay", "due_day_out_of_range", "Due day must be between 1 and 31"))
            }

            // A due day before the statement day is normal -- it falls in the following month --
            // so the two are deliberately not compared against each other.

            val minimumDue = minimumDuePercentText?.trim()?.toBigDecimalOrNull()
            when {
                minimumDuePercentText.isNullOrBlank() ->
                    add(ValidationError("minimumDue", "minimum_required", "Enter the minimum due percentage"))
                minimumDue == null ->
                    add(ValidationError("minimumDue", "minimum_invalid", "That is not a valid percentage"))
                minimumDue.signum() <= 0 || minimumDue > HUNDRED ->
                    add(ValidationError("minimumDue", "minimum_out_of_range", "Minimum due must be between 0 and 100 percent"))
            }

            // Only ever the last four. The field rejects anything else rather than silently
            // truncating, so a full card number pasted in is refused instead of half-stored.
            if (!lastFourDigits.isNullOrBlank() && !lastFourDigits.matches(Regex("\\d{4}"))) {
                add(ValidationError("lastFour", "last_four_invalid", "Enter exactly four digits, or leave blank"))
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid(Unit) else ValidationResult.Invalid(errors)
    }
}

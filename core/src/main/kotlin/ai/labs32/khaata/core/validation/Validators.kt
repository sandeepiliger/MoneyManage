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

package ai.labs32.khaata.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.core.analytics.AnalyticsEvent
import ai.labs32.khaata.core.analytics.AnalyticsProvider
import ai.labs32.khaata.core.analytics.EntryMethod
import ai.labs32.khaata.core.categorize.SuggestionSource
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.CategoryKind
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.TransactionSource
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyParser
import ai.labs32.khaata.core.validation.TransactionInput
import ai.labs32.khaata.core.validation.TransactionValidator
import ai.labs32.khaata.core.validation.ValidationError
import ai.labs32.khaata.core.validation.ValidationResult
import android.net.Uri
import ai.labs32.khaata.core.categorize.QuickCategories
import ai.labs32.khaata.core.entitlement.Feature
import ai.labs32.khaata.core.model.Receipt
import ai.labs32.khaata.data.repository.EntitlementRepository
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.data.repository.ReceiptAttachResult
import ai.labs32.khaata.data.repository.ReceiptRepository
import ai.labs32.khaata.data.repository.StagedReceipt
import ai.labs32.khaata.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

/**
 * State for adding or editing a transaction.
 *
 * The amount is held as raw text rather than as a parsed [Money] so the keypad can show exactly
 * what the user typed, including a trailing decimal point mid-entry, and so validation can
 * explain what is wrong with it rather than silently coercing.
 */
data class TransactionEditUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val type: TransactionType = TransactionType.EXPENSE,
    val amountText: String = "",
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val accountId: String? = null,
    val transferAccountId: String? = null,
    val categoryId: String? = null,
    val merchant: String = "",
    val note: String = "",
    val occurredOn: LocalDate = LocalDate.now(),
    val tags: Set<String> = emptySet(),
    /** What is typed in the tags field, kept as typed so a trailing comma survives recomposition. */
    val tagsText: String = "",
    /** Tags used before, offered as one-tap chips. */
    val knownTags: List<String> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val merchantSuggestions: List<String> = emptyList(),
    /** Why a category was preselected, shown as a subtle hint rather than a silent change. */
    val categoryHint: String? = null,
    /** Category ids this user reaches for most, commonest first. Drives the quick row. */
    val recentCategoryIds: List<String> = emptyList(),
    val errors: List<ValidationError> = emptyList(),
    val overdraftWarning: String? = null,
    val isSaving: Boolean = false,
    val savedTransactionId: String? = null,
    val loadError: String? = null,
    /** Receipts already attached, when an existing transaction is being edited. */
    val savedReceipts: List<Receipt> = emptyList(),
    /** Receipts imported during this entry, written to the database when it is saved. */
    val stagedReceipts: List<StagedReceipt> = emptyList(),
    val canAttachReceipts: Boolean = false,
    val isAttachingReceipt: Boolean = false,
    val receiptError: ReceiptAttachResult? = null,
) {
    fun errorFor(field: String): ValidationError? = errors.firstOrNull { it.field == field }

    /** Saved and staged together, since the per-transaction cap covers both. */
    val receiptCount: Int get() = savedReceipts.size + stagedReceipts.size

    /** Whether the amount is far enough along for the save button to be meaningful. */
    val canSave: Boolean
        get() = !isSaving && amountText.isNotBlank() && accountId != null

    val amountPreview: Money?
        get() = MoneyParser.parse(amountText, currency)

    /**
     * The categories one tap away, most-useful first.
     *
     * The picker used to list every top-level category and nothing else, which meant the
     * forty-two subcategories could not be chosen during entry at all. Now the row is what this
     * person actually uses — subcategories included — and everything else is one tap further, in
     * the searchable sheet.
     */
    val quickCategories: List<Category>
        get() = QuickCategories.forEntry(
            available = relevantCategories,
            recentlyUsedIds = recentCategoryIds,
            selectedId = categoryId,
        )

    /** Categories relevant to the current direction, so an expense picker shows no salary rows. */
    val relevantCategories: List<Category>
        get() = when (type) {
            TransactionType.INCOME -> categories.filter {
                it.kind == CategoryKind.INCOME || it.kind == CategoryKind.BOTH
            }
            TransactionType.EXPENSE -> categories.filter {
                it.kind == CategoryKind.EXPENSE || it.kind == CategoryKind.BOTH
            }
            // Transfers are never categorised: money moving between your own accounts has not
            // been spent on anything.
            TransactionType.TRANSFER -> emptyList()
        }
}

@HiltViewModel
class TransactionEditViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val profileRepository: ProfileRepository,
    private val receiptRepository: ReceiptRepository,
    private val entitlementRepository: EntitlementRepository,
    private val analytics: AnalyticsProvider,
    private val clock: KhaataClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionEditUiState())
    val uiState: StateFlow<TransactionEditUiState> = _uiState.asStateFlow()

    private var editingTransaction: Transaction? = null

    /** Held so a cancelled capture can still clean up the file the camera was handed. */
    private var pendingCapture: ReceiptRepository.CaptureTarget? = null
    private var hadCategorySuggestion = false

    /** Loads accounts and categories, and the existing transaction when editing. */
    fun initialise(transactionId: String?) {
        if (!_uiState.value.isLoading) return

        viewModelScope.launch {
            val canAttach = entitlementRepository.isUnlocked(Feature.RECEIPT_ATTACHMENTS)
            _uiState.update { it.copy(canAttachReceipts = canAttach) }
        }

        viewModelScope.launch {
            val known = runCatching { transactionRepository.observeAllTags().first() }.getOrDefault(emptyList())
            _uiState.update { it.copy(knownTags = known) }
        }

        viewModelScope.launch {
            // Read once rather than observed: the row should not reshuffle under the user's
            // finger because a transaction was saved on another screen mid-entry.
            val recent = runCatching { categoryRepository.mostUsedIds(clock.today()) }
                .getOrDefault(emptyList())
            _uiState.update { it.copy(recentCategoryIds = recent) }
        }

        viewModelScope.launch {
            try {
                val accounts = accountRepository.observeActive().first()
                val categories = categoryRepository.observeActive().first()
                val currency = profileRepository.currency()

                if (transactionId == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            accounts = accounts,
                            categories = categories,
                            currency = currency,
                            // Prefilled with the user's first account, which is the one they use
                            // most in practice, so the common case needs no account tap at all.
                            accountId = accounts.firstOrNull()?.id,
                            occurredOn = clock.today(),
                        )
                    }
                } else {
                    val transaction = transactionRepository.findById(transactionId)
                    if (transaction == null) {
                        _uiState.update {
                            it.copy(isLoading = false, loadError = "This transaction no longer exists.")
                        }
                        return@launch
                    }
                    editingTransaction = transaction
                    val attached = receiptRepository.forTransaction(transaction.id)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isEditing = true,
                            savedReceipts = attached,
                            type = transaction.type,
                            amountText = transaction.amount.toPlainString(),
                            currency = transaction.amount.currency,
                            accountId = transaction.accountId,
                            transferAccountId = transaction.transferAccountId,
                            categoryId = transaction.categoryId,
                            merchant = transaction.merchant.orEmpty(),
                            note = transaction.note.orEmpty(),
                            occurredOn = transaction.occurredOn,
                            tags = transaction.tags,
                            tagsText = transaction.tags.joinToString(", "),
                            accounts = accounts,
                            categories = categories,
                        )
                    }
                }
            } catch (error: Exception) {
                KhaataLog.e(TAG, "Failed to load transaction form", error)
                _uiState.update {
                    it.copy(isLoading = false, loadError = "We could not open this. Please try again.")
                }
            }
        }
    }

    // ---- Field updates -----------------------------------------------------------------------

    fun onTypeChange(type: TransactionType) {
        _uiState.update { state ->
            state.copy(
                type = type,
                // A category carried over from an expense makes no sense on a transfer, and an
                // expense category on an income row would corrupt the reports.
                categoryId = when (type) {
                    TransactionType.TRANSFER -> null
                    // Kept only if it is a category of the new kind: "Groceries" carried onto an
                    // income row put spending categories into the income report.
                    else -> state.categoryId?.takeIf { id ->
                        val kind = state.categories.firstOrNull { it.id == id }?.kind
                        kind == null ||
                            kind == ai.labs32.khaata.core.model.CategoryKind.BOTH ||
                            kind.name == type.name
                    }
                },
                transferAccountId = if (type == TransactionType.TRANSFER) state.transferAccountId else null,
                errors = emptyList(),
            )
        }
        refreshOverdraftWarning()
    }

    /**
     * Handles a keypad press.
     *
     * Input is constrained here rather than validated afterwards, so it is impossible to type
     * "1..2" or an amount with three decimal places in the first place.
     */
    fun onKeypadInput(key: KeypadKey) {
        _uiState.update { state ->
            val current = state.amountText
            val updated = when (key) {
                is KeypadKey.Digit -> {
                    val next = if (current == "0") key.value.toString() else current + key.value
                    if (isValidAmountText(next)) next else current
                }
                KeypadKey.Decimal -> if (current.contains('.')) current else {
                    if (current.isEmpty()) "0." else "$current."
                }
                KeypadKey.Backspace -> current.dropLast(1)
                KeypadKey.Clear -> ""
            }
            state.copy(amountText = updated, errors = state.errors.filterNot { it.field == "amount" })
        }
        refreshOverdraftWarning()
    }

    /** Rejects more than two decimal places and absurdly long entries as they are typed. */
    private fun isValidAmountText(text: String): Boolean {
        if (text.length > MAX_AMOUNT_LENGTH) return false
        val decimalIndex = text.indexOf('.')
        if (decimalIndex >= 0 && text.length - decimalIndex - 1 > 2) return false
        return true
    }

    fun onAmountTextChange(text: String) {
        val filtered = text.filter { it.isDigit() || it == '.' }
        if (!isValidAmountText(filtered)) return
        _uiState.update {
            it.copy(amountText = filtered, errors = it.errors.filterNot { e -> e.field == "amount" })
        }
        refreshOverdraftWarning()
    }

    fun onAccountChange(accountId: String) {
        _uiState.update {
            it.copy(accountId = accountId, errors = it.errors.filterNot { e -> e.field == "account" })
        }
        refreshOverdraftWarning()
    }

    fun onTransferAccountChange(accountId: String) {
        _uiState.update {
            it.copy(
                transferAccountId = accountId,
                errors = it.errors.filterNot { e -> e.field == "transferAccount" },
            )
        }
    }

    fun onCategoryChange(categoryId: String) {
        _uiState.update {
            it.copy(
                categoryId = categoryId,
                // An explicit pick clears the "usually Food" hint, since it is no longer a guess.
                categoryHint = null,
                errors = it.errors.filterNot { e -> e.field == "category" },
            )
        }
    }

    /**
     * Updates the merchant and, when a rule matches, preselects its category.
     *
     * The suggestion never overwrites a category the user has already chosen — a guess must not
     * undo a decision.
     */
    fun onMerchantChange(merchant: String) {
        _uiState.update { it.copy(merchant = merchant) }

        viewModelScope.launch {
            val suggestions = transactionRepository.merchantSuggestions(merchant)
            val suggestion = categoryRepository.suggestFor(merchant)

            _uiState.update { state ->
                val shouldApply = suggestion != null &&
                    state.categoryId == null &&
                    state.type != TransactionType.TRANSFER

                if (shouldApply) hadCategorySuggestion = true

                state.copy(
                    merchantSuggestions = suggestions,
                    categoryId = if (shouldApply) suggestion.categoryId else state.categoryId,
                    categoryHint = if (shouldApply) {
                        state.categories.firstOrNull { it.id == suggestion.categoryId }?.name
                    } else {
                        state.categoryHint
                    },
                    accountId = if (shouldApply && suggestion.accountId != null && !state.isEditing) {
                        suggestion.accountId
                    } else {
                        state.accountId
                    },
                )
            }
        }
    }

    fun onNoteChange(note: String) = _uiState.update { it.copy(note = note) }

    fun onDateChange(date: LocalDate) = _uiState.update { it.copy(occurredOn = date) }

    fun onTagsChange(tags: Set<String>) = _uiState.update {
        it.copy(tags = tags, tagsText = tags.joinToString(", "))
    }

    /** Tags typed as a comma-separated list: "trip, goa". */
    fun onTagsTextChange(text: String) = _uiState.update {
        it.copy(tagsText = text, tags = parseTags(text))
    }

    /** Adds a previously used tag from its chip, or removes it if it is already there. */
    fun onKnownTagToggle(tag: String) {
        val current = _uiState.value.tags
        val existing = current.firstOrNull { it.equals(tag, ignoreCase = true) }
        onTagsChange(if (existing != null) current - existing else current + tag)
    }

    private fun parseTags(text: String): Set<String> =
        text.split(',')
            .map { raw -> raw.filterNot { it.isISOControl() }.trim().take(MAX_TAG_LENGTH) }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .toCollection(LinkedHashSet())

    // ---- Save --------------------------------------------------------------------------------

    /**
     * Validates and saves.
     *
     * Validation runs against the whole form and surfaces every problem at once, so the user
     * fixes it in one pass rather than one field at a time.
     */
    // ---- Receipts ------------------------------------------------------------------------------

    /**
     * Imports a picked or captured image.
     *
     * While editing an existing transaction the row is written straight away, because there is a
     * transaction for it to belong to. During a new entry there is not, so the image is staged and
     * the row waits for [save] — see [StagedReceipt].
     */
    fun attachReceipt(source: Uri?, captured: Boolean) {
        // Held whether or not the capture succeeded: a cancelled one leaves a zero-byte file the
        // camera created, and nothing else will ever clean it up.
        val staging = pendingCapture.takeIf { captured }
        pendingCapture = null

        if (source == null) {
            viewModelScope.launch { staging?.let { receiptRepository.discardCapture(it) } }
            return
        }
        if (_uiState.value.isAttachingReceipt) {
            // Dropping the result still means owning the file the camera wrote.
            viewModelScope.launch { staging?.let { receiptRepository.discardCapture(it) } }
            return
        }
        _uiState.update { it.copy(isAttachingReceipt = true) }

        viewModelScope.launch {
            val existing = editingTransaction
            val result = if (existing != null) {
                receiptRepository.attach(existing.id, source)
            } else {
                receiptRepository.stage(source, alreadyStaged = _uiState.value.stagedReceipts.size)
            }
            // After the import, which is the last thing that reads it.
            staging?.let { receiptRepository.discardCapture(it) }

            _uiState.update { state ->
                when (result) {
                    is ReceiptAttachResult.Attached -> state.copy(
                        isAttachingReceipt = false,
                        savedReceipts = state.savedReceipts + result.receipt,
                    )

                    is ReceiptAttachResult.Staged -> state.copy(
                        isAttachingReceipt = false,
                        stagedReceipts = state.stagedReceipts + result.staged,
                    )

                    else -> state.copy(isAttachingReceipt = false, receiptError = result)
                }
            }
        }
    }

    /** Removes a receipt, whether it is already saved or only staged. Keyed as the strip keys it. */
    fun removeReceipt(key: String) {
        viewModelScope.launch {
            val state = _uiState.value
            val saved = state.savedReceipts.firstOrNull { it.id == key }
            val staged = state.stagedReceipts.firstOrNull { it.relativePath == key }

            when {
                saved != null -> {
                    receiptRepository.delete(saved)
                    _uiState.update { it.copy(savedReceipts = it.savedReceipts - saved) }
                }

                staged != null -> {
                    receiptRepository.discardStaged(listOf(staged))
                    _uiState.update { it.copy(stagedReceipts = it.stagedReceipts - staged) }
                }
            }
        }
    }

    /** A camera needs a file to write into; the repository stages it outside private storage. */
    fun newCaptureTarget(): ReceiptRepository.CaptureTarget =
        receiptRepository.newCaptureTarget().also { pendingCapture = it }

    fun fileFor(receipt: Receipt): File = receiptRepository.fileFor(receipt)

    fun fileFor(staged: StagedReceipt): File = receiptRepository.fileFor(staged)

    fun consumeReceiptError() = _uiState.update { it.copy(receiptError = null) }

    /**
     * Drops images staged for an entry the user walked away from.
     *
     * Hooked to [onCleared] rather than to the composable leaving the screen, because those are
     * not the same event: a rotation disposes the composable and keeps this view model, and
     * discarding there would delete the receipts of anyone who turned their phone sideways
     * mid-entry. By the time this runs after a successful save there is nothing staged left —
     * committing clears the list.
     *
     * The orphan sweep would reclaim these eventually, but "eventually" is a week, and a user who
     * cancelled an entry has no reason to be carrying its photos until then.
     */
    override fun onCleared() {
        super.onCleared()
        val staged = _uiState.value.stagedReceipts
        if (staged.isEmpty()) return
        // Deliberately not viewModelScope: that is already cancelled by the time this runs. The
        // repository's own scope outlives it.
        receiptRepository.discardStagedDetached(staged)
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return

        val input = TransactionInput(
            type = state.type,
            amountText = state.amountText,
            currency = state.currency,
            accountId = state.accountId.orEmpty(),
            transferAccountId = state.transferAccountId,
            categoryId = state.categoryId,
            merchant = state.merchant,
            note = state.note,
            occurredOn = state.occurredOn,
            tags = state.tags,
        )

        when (val result = TransactionValidator.validate(input, clock.today())) {
            is ValidationResult.Invalid -> {
                _uiState.update { it.copy(errors = result.errors) }
                return
            }
            is ValidationResult.Valid -> Unit
        }

        val amount = MoneyParser.parse(state.amountText, state.currency) ?: return
        _uiState.update { it.copy(isSaving = true, errors = emptyList()) }

        viewModelScope.launch {
            try {
                val existing = editingTransaction
                if (existing != null) {
                    transactionRepository.update(
                        existing.copy(
                            type = state.type,
                            amount = amount,
                            accountId = state.accountId!!,
                            transferAccountId = state.transferAccountId,
                            categoryId = state.categoryId,
                            merchant = state.merchant.trim().takeIf { it.isNotBlank() },
                            note = state.note.trim().takeIf { it.isNotBlank() },
                            occurredOn = state.occurredOn,
                            tags = state.tags,
                        ),
                    )
                    analytics.track(AnalyticsEvent.TransactionEdited)
                    commitStagedReceipts(existing.id)
                    _uiState.update { it.copy(isSaving = false, savedTransactionId = existing.id) }
                } else {
                    val id = transactionRepository.create(
                        type = state.type,
                        amount = amount,
                        accountId = state.accountId!!,
                        categoryId = state.categoryId,
                        transferAccountId = state.transferAccountId,
                        merchant = state.merchant.trim().takeIf { it.isNotBlank() },
                        note = state.note.trim().takeIf { it.isNotBlank() },
                        occurredOn = state.occurredOn,
                        tags = state.tags,
                        source = TransactionSource.QUICK_ADD,
                    )
                    analytics.track(
                        AnalyticsEvent.TransactionCreated(
                            entryMethod = EntryMethod.QUICK_ADD,
                            type = state.type.name,
                            hadCategorySuggestion = hadCategorySuggestion,
                        ),
                    )
                    commitStagedReceipts(id)
                    _uiState.update { it.copy(isSaving = false, savedTransactionId = id) }
                }
            } catch (error: Exception) {
                KhaataLog.e(TAG, "Failed to save transaction", error)
                analytics.track(AnalyticsEvent.ErrorEncountered("transaction_save"))
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errors = listOf(
                            ValidationError("form", "save_failed", "We could not save that. Please try again."),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Writes the rows for images staged during this entry.
     *
     * Cleared from state first so a save that somehow ran twice could not write them twice, and
     * so leaving the screen afterwards does not discard images that now belong to a transaction.
     */
    private suspend fun commitStagedReceipts(transactionId: String) {
        val staged = _uiState.value.stagedReceipts
        if (staged.isEmpty()) return
        _uiState.update { it.copy(stagedReceipts = emptyList()) }
        receiptRepository.commitStaged(transactionId, staged)
    }

    /**
     * Warns when an expense would take a non-credit account below zero.
     *
     * A warning rather than a block: cash accounts drift out of sync with reality all the time,
     * and refusing the entry would teach the user to stop recording things.
     */
    private fun refreshOverdraftWarning() {
        val state = _uiState.value
        val accountId = state.accountId ?: return
        val amount = state.amountPreview ?: run {
            _uiState.update { it.copy(overdraftWarning = null) }
            return
        }

        viewModelScope.launch {
            val account = state.accounts.firstOrNull { it.id == accountId } ?: return@launch
            val balance = accountRepository.balanceOf(accountId) ?: return@launch
            val warning = TransactionValidator.overdraftWarning(account, balance, amount, state.type)
            _uiState.update { it.copy(overdraftWarning = warning) }
        }
    }

    private companion object {
        const val TAG = "TransactionEditViewModel"

        /** Ten crore with paise is far beyond any personal transaction. */
        const val MAX_AMOUNT_LENGTH = 12

        /** Long enough for "goa trip 2026", short enough to stay one chip wide. */
        const val MAX_TAG_LENGTH = 30
    }
}

/** A key on the amount keypad. */
sealed interface KeypadKey {
    data class Digit(val value: Int) : KeypadKey
    data object Decimal : KeypadKey
    data object Backspace : KeypadKey
    data object Clear : KeypadKey
}

package ai.labs32.khaata.feature.transactions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.labs32.khaata.R
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.money.MoneyStyle
import ai.labs32.khaata.core.money.SignStyle
import ai.labs32.khaata.core.ui.components.ErrorState
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.theme.KhaataShapeTokens
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import java.time.format.DateTimeFormatter

/**
 * Add or edit a transaction.
 *
 * The screen is arranged around one claim: recording a spend should take two or three seconds.
 * The amount keypad is on screen immediately with no field to focus first, the account is
 * prefilled, and the category is preselected from the merchant when a rule matches. Everything
 * optional — merchant, note, date, tags — sits below the fold and never blocks saving.
 *
 * The reference apps mostly open a form with an amount field that needs focusing and a category
 * picker that needs a decision before anything can be saved. That is fine once and tiresome the
 * fiftieth time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditScreen(
    transactionId: String?,
    onDone: () -> Unit,
    onDescribeInstead: (() -> Unit)?,
    viewModel: TransactionEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(transactionId) { viewModel.initialise(transactionId) }

    LaunchedEffect(state.savedTransactionId) {
        if (state.savedTransactionId != null) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = {
                    Text(
                        stringResource(
                            if (state.isEditing) R.string.transaction_edit_title
                            else R.string.transaction_add_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_close),
                        )
                    }
                },
                actions = {
                    if (onDescribeInstead != null) {
                        TextButton(onClick = onDescribeInstead) {
                            Text(stringResource(R.string.quick_add_natural_language))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        when {
            state.isLoading -> LoadingState(Modifier.padding(padding))

            state.loadError != null -> ErrorState(
                message = state.loadError!!,
                modifier = Modifier.padding(padding),
                onRetry = { viewModel.initialise(transactionId) },
            )

            else -> TransactionEditContent(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun TransactionEditContent(
    state: TransactionEditUiState,
    viewModel: TransactionEditViewModel,
    modifier: Modifier = Modifier,
) {
    var showOptionalFields by remember { mutableStateOf(state.isEditing) }
    val spacing = KhaataTheme.spacing

    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal),
        ) {
            Spacer(Modifier.height(spacing.small))

            TypeSelector(
                selected = state.type,
                onSelect = viewModel::onTypeChange,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.large))

            AmountDisplay(
                amountText = state.amountText,
                currencySymbol = state.currency.symbol,
                type = state.type,
                error = state.errorFor("amount")?.message,
            )

            state.overdraftWarning?.let { warning ->
                Spacer(Modifier.height(spacing.small))
                WarningRow(warning)
            }

            Spacer(Modifier.height(spacing.large))

            // Account first, because a transaction cannot be saved without one.
            AccountSelector(
                label = stringResource(R.string.transaction_account),
                accounts = state.accounts,
                selectedId = state.accountId,
                onSelect = viewModel::onAccountChange,
                error = state.errorFor("account")?.message,
            )

            if (state.type == TransactionType.TRANSFER) {
                Spacer(Modifier.height(spacing.default))
                AccountSelector(
                    label = stringResource(R.string.transaction_to_account),
                    accounts = state.accounts.filter { it.id != state.accountId },
                    selectedId = state.transferAccountId,
                    onSelect = viewModel::onTransferAccountChange,
                    error = state.errorFor("transferAccount")?.message,
                    leadingIcon = Icons.Default.SwapHoriz,
                )
            } else {
                Spacer(Modifier.height(spacing.default))
                CategorySelector(
                    categories = state.relevantCategories,
                    selectedId = state.categoryId,
                    hint = state.categoryHint,
                    onSelect = viewModel::onCategoryChange,
                    error = state.errorFor("category")?.message,
                )
            }

            Spacer(Modifier.height(spacing.default))

            if (showOptionalFields) {
                OptionalFields(state = state, viewModel = viewModel)
            } else {
                TextButton(onClick = { showOptionalFields = true }) {
                    Text(stringResource(R.string.quick_add_more_options))
                }
            }

            state.errorFor("form")?.let { error ->
                Spacer(Modifier.height(spacing.small))
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(spacing.default))
        }

        AmountKeypad(
            onKey = viewModel::onKeypadInput,
            onSave = viewModel::save,
            canSave = state.canSave,
            isSaving = state.isSaving,
        )
    }
}

// ---- Type selector ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSelector(
    selected: TransactionType,
    onSelect: (TransactionType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val types = listOf(
        TransactionType.EXPENSE to R.string.transaction_expense,
        TransactionType.INCOME to R.string.transaction_income,
        TransactionType.TRANSFER to R.string.transaction_transfer,
    )

    SingleChoiceSegmentedButtonRow(modifier) {
        types.forEachIndexed { index, (type, labelRes) ->
            SegmentedButton(
                selected = selected == type,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index, types.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = when (type) {
                        TransactionType.EXPENSE -> KhaataTheme.money.expenseContainer
                        TransactionType.INCOME -> KhaataTheme.money.incomeContainer
                        TransactionType.TRANSFER -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    activeContentColor = when (type) {
                        TransactionType.EXPENSE -> KhaataTheme.money.onExpenseContainer
                        TransactionType.INCOME -> KhaataTheme.money.onIncomeContainer
                        TransactionType.TRANSFER -> MaterialTheme.colorScheme.onSurface
                    },
                ),
            ) {
                Text(stringResource(labelRes), maxLines = 1)
            }
        }
    }
}

// ---- Amount ----------------------------------------------------------------------------------

/**
 * The amount, shown at display size.
 *
 * The colour follows the transaction type, but the direction is also carried by the sign and by
 * the selected segment above, so it never depends on hue alone.
 */
@Composable
private fun AmountDisplay(
    amountText: String,
    currencySymbol: String,
    type: TransactionType,
    error: String?,
) {
    val money = KhaataTheme.money
    val color = when (type) {
        TransactionType.EXPENSE -> money.expense
        TransactionType.INCOME -> money.income
        TransactionType.TRANSFER -> MaterialTheme.colorScheme.onSurface
    }
    val display = amountText.ifBlank { "0" }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = currencySymbol,
                style = MaterialTheme.typography.headlineMedium,
                color = color.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = display,
                style = KhaataTextStyles.keypadAmount,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun WarningRow(message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(KhaataShapeTokens.cardCompact)
            .background(KhaataTheme.money.warningContainer)
            .padding(12.dp),
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = KhaataTheme.money.onWarningContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = KhaataTheme.money.onWarningContainer,
        )
    }
}

// ---- Selectors -------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSelector(
    label: String,
    accounts: List<ai.labs32.khaata.core.model.Account>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    error: String?,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(accounts, key = { it.id }) { account ->
                FilterChip(
                    selected = account.id == selectedId,
                    onClick = { onSelect(account.id) },
                    label = { Text(account.name, maxLines = 1) },
                    leadingIcon = if (leadingIcon != null && account.id == selectedId) {
                        { Icon(leadingIcon, contentDescription = null, Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * The category picker.
 *
 * A horizontally scrolling row of chips rather than a modal picker: the common categories are
 * visible and one tap away, which is the difference between a two-second entry and a
 * five-second one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySelector(
    categories: List<ai.labs32.khaata.core.model.Category>,
    selectedId: String?,
    hint: String?,
    onSelect: (String) -> Unit,
    error: String?,
) {
    // Top-level categories first, then the selected subcategory if one is chosen, so the row
    // stays short without hiding the current choice.
    val visible = remember(categories, selectedId) {
        val topLevel = categories.filter { it.parentId == null }
        val selected = categories.firstOrNull { it.id == selectedId }
        if (selected != null && selected.parentId != null) listOf(selected) + topLevel else topLevel
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.transaction_category),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.transaction_suggestion_hint, hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible, key = { it.id }) { category ->
                FilterChip(
                    selected = category.id == selectedId,
                    onClick = { onSelect(category.id) },
                    label = { Text(category.name, maxLines = 1) },
                    leadingIcon = if (category.id == selectedId) {
                        { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

// ---- Optional fields -------------------------------------------------------------------------

@Composable
private fun OptionalFields(
    state: TransactionEditUiState,
    viewModel: TransactionEditViewModel,
) {
    val spacing = KhaataTheme.spacing

    Column {
        OutlinedTextField(
            value = state.merchant,
            onValueChange = viewModel::onMerchantChange,
            label = { Text(stringResource(R.string.transaction_merchant)) },
            leadingIcon = { Icon(Icons.Default.Storefront, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.merchantSuggestions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.merchantSuggestions.forEach { suggestion ->
                    androidx.compose.material3.SuggestionChip(
                        onClick = { viewModel.onMerchantChange(suggestion) },
                        label = { Text(suggestion, maxLines = 1) },
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.medium))

        OutlinedTextField(
            value = state.note,
            onValueChange = viewModel::onNoteChange,
            label = { Text(stringResource(R.string.transaction_note)) },
            leadingIcon = { Icon(Icons.Default.Notes, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 3,
            isError = state.errorFor("note") != null,
            supportingText = state.errorFor("note")?.let { { Text(it.message) } },
        )

        Spacer(Modifier.height(spacing.medium))

        DateRow(
            date = state.occurredOn,
            error = state.errorFor("date")?.message,
            onPickDate = viewModel::onDateChange,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(
    date: java.time.LocalDate,
    error: String?,
    onPickDate: (java.time.LocalDate) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("d MMM yyyy") }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(KhaataShapeTokens.cardCompact)
                .clickable { showPicker = true }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.CalendarToday,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.transaction_date),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = date.format(formatter),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    if (showPicker) {
        val pickerState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = date
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onPickDate(
                                java.time.Instant.ofEpochMilli(millis)
                                    .atZone(java.time.ZoneOffset.UTC)
                                    .toLocalDate(),
                            )
                        }
                        showPicker = false
                    },
                ) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            androidx.compose.material3.DatePicker(state = pickerState)
        }
    }
}

// ---- Keypad ----------------------------------------------------------------------------------

/**
 * The amount keypad.
 *
 * A custom keypad rather than the system numeric keyboard, for three reasons: it is on screen
 * immediately with nothing to focus, the keys are far larger than a soft keyboard's, and the
 * save button sits inside the same block so entry finishes without the hand moving.
 *
 * There is deliberately no ad anywhere near this component.
 */
@Composable
private fun AmountKeypad(
    onKey: (KeypadKey) -> Unit,
    onSave: () -> Unit,
    canSave: Boolean,
    isSaving: Boolean,
) {
    val rows = listOf(
        listOf(KeypadKey.Digit(1), KeypadKey.Digit(2), KeypadKey.Digit(3)),
        listOf(KeypadKey.Digit(4), KeypadKey.Digit(5), KeypadKey.Digit(6)),
        listOf(KeypadKey.Digit(7), KeypadKey.Digit(8), KeypadKey.Digit(9)),
        listOf(KeypadKey.Decimal, KeypadKey.Digit(0), KeypadKey.Backspace),
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    KeypadButton(key = key, onClick = { onKey(key) }, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = onSave,
            enabled = canSave,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) {
            if (isSaving) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    stringResource(R.string.action_save),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun KeypadButton(
    key: KeypadKey,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (key) {
        is KeypadKey.Digit -> key.value.toString()
        KeypadKey.Decimal -> "."
        KeypadKey.Backspace -> null
        KeypadKey.Clear -> null
    }
    val description = when (key) {
        is KeypadKey.Digit -> key.value.toString()
        KeypadKey.Decimal -> "decimal point"
        KeypadKey.Backspace -> "backspace"
        KeypadKey.Clear -> "clear"
    }

    Box(
        modifier = modifier
            // Comfortably above the 48dp minimum: this is the most-tapped control in the app.
            .heightIn(min = 56.dp)
            .clip(KhaataShapeTokens.keypadKey)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Icon(
                imageVector = Icons.Default.Backspace,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

package ai.labs32.khaata.feature.transactions

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardCapitalization
import ai.labs32.khaata.core.money.MoneyParser
import ai.labs32.khaata.feature.shared.relativeDateLabel
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import ai.labs32.khaata.core.ui.components.CategoryIcons
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.theme.KhaataShapeTokens
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.feature.receipts.ReceiptSourceSheet
import ai.labs32.khaata.feature.receipts.ReceiptStrip
import ai.labs32.khaata.feature.receipts.ReceiptTile
import ai.labs32.khaata.feature.receipts.ReceiptViewerDialog
import ai.labs32.khaata.feature.receipts.receiptErrorText
import java.time.format.DateTimeFormatter

/**
 * Add or edit a transaction.
 *
 * The screen is arranged around one claim: recording a spend should take two or three seconds.
 * The amount keypad is on screen immediately with no field to focus first, the account is
 * prefilled, and the category is preselected from the merchant when a rule matches. The merchant
 * sits right under the amount, because it is what picks the category; date, note and tags are one
 * row of chips, visible but never blocking saving. None of it hides behind a "more options" link:
 * a date nobody saw was the commonest way a spend landed on the wrong day.
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
    onOpenPaywall: () -> Unit,
    viewModel: TransactionEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showReceiptSource by remember { mutableStateOf(false) }
    var viewingReceipt by remember { mutableStateOf<ReceiptTile?>(null) }
    var showCategorySheet by remember { mutableStateOf(false) }

    LaunchedEffect(transactionId) { viewModel.initialise(transactionId) }

    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.savedTransactionId) {
        if (state.savedTransactionId != null) {
            // A firm tick on save, so a one-handed entry is felt to have landed without looking.
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onDone()
        }
    }

    // The system photo picker: no storage permission, and the app receives only the one image
    // the user chose.
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> viewModel.attachReceipt(uri, captured = false) }

    // TakePicture writes into a URI we hand the camera app, so this app needs no CAMERA
    // permission of its own.
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved -> viewModel.attachReceipt(captureUri.takeIf { saved }, captured = true) }

    val receiptMessage = state.receiptError?.let { receiptErrorText(it) }
    LaunchedEffect(receiptMessage) {
        if (receiptMessage != null) {
            snackbarHostState.showSnackbar(receiptMessage)
            viewModel.consumeReceiptError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                onAddReceipt = {
                    if (state.canAttachReceipts) showReceiptSource = true else onOpenPaywall()
                },
                onOpenReceipt = { viewingReceipt = it },
                onBrowseCategories = { showCategorySheet = true },
            )
        }
    }

    if (showCategorySheet) {
        CategoryPickerSheet(
            categories = state.relevantCategories,
            selectedId = state.categoryId,
            onSelect = { id ->
                viewModel.onCategoryChange(id)
                showCategorySheet = false
            },
            onDismiss = { showCategorySheet = false },
        )
    }

    if (showReceiptSource) {
        ReceiptSourceSheet(
            onCamera = {
                showReceiptSource = false
                val target = viewModel.newCaptureTarget()
                captureUri = target.uri
                takePhoto.launch(target.uri)
            },
            onGallery = {
                showReceiptSource = false
                pickPhoto.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onDismiss = { showReceiptSource = false },
        )
    }

    viewingReceipt?.let { tile ->
        ReceiptViewerDialog(
            file = tile.file,
            // Nothing to share yet for a staged image, and a saved one is shared from the detail
            // screen, so this viewer is for checking the photo came out readable and removing it
            // if it did not.
            onShare = null,
            onDelete = {
                viewModel.removeReceipt(tile.key)
                viewingReceipt = null
            },
            onDismiss = { viewingReceipt = null },
        )
    }
}

@Composable
private fun TransactionEditContent(
    state: TransactionEditUiState,
    viewModel: TransactionEditViewModel,
    modifier: Modifier = Modifier,
    onAddReceipt: () -> Unit,
    onOpenReceipt: (ReceiptTile) -> Unit,
    onBrowseCategories: () -> Unit,
) {
    // Open when there is something in them already, so editing never hides a saved note.
    var showNote by rememberSaveable { mutableStateOf(state.note.isNotBlank()) }
    var showTags by rememberSaveable { mutableStateOf(state.tagsText.isNotBlank()) }
    val spacing = KhaataTheme.spacing
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_DP

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

            if (state.type != TransactionType.TRANSFER) {
                Spacer(Modifier.height(spacing.small))
                MerchantField(state = state, onChange = viewModel::onMerchantChange)
            }

            Spacer(Modifier.height(if (compact) spacing.default else spacing.large))

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
                    quick = state.quickCategories,
                    selectedId = state.categoryId,
                    hint = state.categoryHint,
                    onSelect = viewModel::onCategoryChange,
                    onBrowseAll = onBrowseCategories,
                    error = state.errorFor("category")?.message,
                )
            }

            Spacer(Modifier.height(spacing.default))

            // Above "more options" rather than inside it: the bill is in the user's hand at the
            // moment they are typing the amount, and a receipt hidden behind a disclosure is one
            // they will attach later from the transaction — which is exactly the detour this is
            // here to remove.
            ReceiptStrip(
                tiles = state.savedReceipts.map {
                    ReceiptTile(key = it.id, file = viewModel.fileFor(it))
                } + state.stagedReceipts.map {
                    ReceiptTile(key = it.relativePath, file = viewModel.fileFor(it))
                },
                canAttach = state.canAttachReceipts,
                isAttaching = state.isAttachingReceipt,
                onAdd = onAddReceipt,
                onOpen = onOpenReceipt,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.default))

            DetailChips(
                date = state.occurredOn,
                dateError = state.errorFor("date")?.message,
                onPickDate = viewModel::onDateChange,
                noteOpen = showNote || state.note.isNotBlank(),
                onToggleNote = { showNote = !showNote },
                tagsOpen = showTags || state.tagsText.isNotBlank(),
                onToggleTags = { showTags = !showTags },
            )

            if (showNote || state.note.isNotBlank()) {
                Spacer(Modifier.height(spacing.medium))
                NoteField(state = state, onChange = viewModel::onNoteChange)
            }

            if (showTags || state.tagsText.isNotBlank()) {
                Spacer(Modifier.height(spacing.medium))
                TagsField(state = state, viewModel = viewModel)
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

        val amount = MoneyParser.parse(state.amountText, state.currency)?.takeIf { it.isPositive }
        AmountKeypad(
            onKey = viewModel::onKeypadInput,
            onSave = viewModel::save,
            canSave = state.canSave,
            isSaving = state.isSaving,
            // Says what it will do -- "Save ₹450" -- so the figure is confirmed at the moment of
            // pressing, not discovered wrong afterwards in the list.
            saveLabel = amount?.let { stringResource(R.string.transaction_save_amount, MoneyFormatter.plain(it)) }
                ?: stringResource(R.string.action_save),
            compact = compact,
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
    quick: List<ai.labs32.khaata.core.model.Category>,
    selectedId: String?,
    hint: String?,
    onSelect: (String) -> Unit,
    onBrowseAll: () -> Unit,
    error: String?,
) {
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
            items(quick, key = { it.id }) { category ->
                FilterChip(
                    selected = category.id == selectedId,
                    onClick = { onSelect(category.id) },
                    label = { Text(category.name, maxLines = 1) },
                    // Always an icon, never only on selection. A row of identical text chips has
                    // to be read one by one; a row of distinct glyphs is scanned at a glance, and
                    // scanning is what someone standing at a counter is actually doing.
                    leadingIcon = {
                        Icon(
                            imageVector = CategoryIcons[category.iconKey],
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }

            // Everything not in the quick row lives one tap away, searchable. Last rather than
            // first so it never displaces a category the user could have tapped directly.
            item(key = "__browse_all") {
                FilterChip(
                    selected = false,
                    onClick = onBrowseAll,
                    label = { Text(stringResource(R.string.category_browse_all), maxLines = 1) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
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

// ---- Details -------------------------------------------------------------------------------

/**
 * Where the money went. Directly under the amount, because the merchant is what the category
 * suggestion is read from -- typing "Swiggy" first is what makes the right chip already selected.
 */
@Composable
private fun MerchantField(
    state: TransactionEditUiState,
    onChange: (String) -> Unit,
) {
    Column {
        OutlinedTextField(
            value = state.merchant,
            onValueChange = onChange,
            placeholder = { Text(stringResource(R.string.transaction_merchant)) },
            leadingIcon = { Icon(Icons.Default.Storefront, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
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
                        onClick = { onChange(suggestion) },
                        label = { Text(suggestion, maxLines = 1) },
                    )
                }
            }
        }
    }
}

/**
 * Date, note and tags as one row of chips.
 *
 * The date chip always shows the date the entry will be saved on, so "today" is confirmed at a
 * glance and a spend from yesterday is one tap to fix. Note and tags open their fields in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailChips(
    date: java.time.LocalDate,
    dateError: String?,
    onPickDate: (java.time.LocalDate) -> Unit,
    noteOpen: Boolean,
    onToggleNote: () -> Unit,
    tagsOpen: Boolean,
    onToggleTags: () -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val selectedColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = date != java.time.LocalDate.now(),
                onClick = { showPicker = true },
                label = {
                    Text(
                        stringResource(R.string.transaction_date) + ": " + relativeDateLabel(date),
                        maxLines = 1,
                    )
                },
                leadingIcon = { Icon(Icons.Default.CalendarToday, contentDescription = null, Modifier.size(18.dp)) },
                colors = selectedColors,
            )
            FilterChip(
                selected = noteOpen,
                onClick = onToggleNote,
                label = { Text(stringResource(R.string.transaction_note), maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Notes, contentDescription = null, Modifier.size(18.dp)) },
                colors = selectedColors,
            )
            FilterChip(
                selected = tagsOpen,
                onClick = onToggleTags,
                label = { Text(stringResource(R.string.transaction_tags), maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Sell, contentDescription = null, Modifier.size(18.dp)) },
                colors = selectedColors,
            )
        }
        if (dateError != null) {
            Text(dateError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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

@Composable
private fun NoteField(
    state: TransactionEditUiState,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = state.note,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.transaction_note)) },
        leadingIcon = { Icon(Icons.Default.Notes, contentDescription = null) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 1,
        maxLines = 3,
        isError = state.errorFor("note") != null,
        supportingText = state.errorFor("note")?.let { { Text(it.message) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagsField(
    state: TransactionEditUiState,
    viewModel: TransactionEditViewModel,
) {
    Column {
        OutlinedTextField(
            value = state.tagsText,
            onValueChange = viewModel::onTagsTextChange,
            label = { Text(stringResource(R.string.transaction_tags)) },
            placeholder = { Text(stringResource(R.string.transaction_tags_hint)) },
            leadingIcon = { Icon(Icons.Default.Sell, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        // Tags only help if they are spelled the same every time, so the ones already in use are
        // a tap away rather than retyped.
        if (state.knownTags.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = KhaataTheme.spacing.small),
            ) {
                items(state.knownTags.take(MAX_TAG_SUGGESTIONS)) { tag ->
                    FilterChip(
                        selected = state.tags.any { it.equals(tag, ignoreCase = true) },
                        onClick = { viewModel.onKnownTagToggle(tag) },
                        label = { Text(tag, maxLines = 1) },
                    )
                }
            }
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
    saveLabel: String,
    compact: Boolean,
) {
    val haptics = LocalHapticFeedback.current
    // On a short screen the keys give up a little height so the merchant and category above
    // stay on screen with the keyboard; they never drop below the 48dp touch minimum.
    val keyHeight = if (compact) 48.dp else 56.dp
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
                    KeypadButton(
                        key = key,
                        onClick = {
                            // A light tick per key, the feel of a real keypad, so a typed amount
                            // can be trusted without watching every digit land.
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onKey(key)
                        },
                        height = keyHeight,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
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
                    saveLabel,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun KeypadButton(
    key: KeypadKey,
    onClick: () -> Unit,
    height: androidx.compose.ui.unit.Dp,
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
        KeypadKey.Decimal -> stringResource(R.string.a11y_decimal_point)
        KeypadKey.Backspace -> stringResource(R.string.a11y_backspace)
        KeypadKey.Clear -> stringResource(R.string.action_clear)
    }

    Box(
        modifier = modifier
            // At or above the 48dp minimum: this is the most-tapped control in the app.
            .heightIn(min = height)
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

/** Enough chips to cover the tags someone actually reuses without turning into a list. */
private const val MAX_TAG_SUGGESTIONS = 12

/** Below this height the keypad tightens so the fields above it stay in view. */
private const val COMPACT_HEIGHT_DP = 700

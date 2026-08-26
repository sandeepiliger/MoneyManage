package ai.labs32.khaata.feature.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.CategoryGroup
import ai.labs32.khaata.core.model.CategoryKind
import ai.labs32.khaata.core.ui.components.CategoryIcons
import ai.labs32.khaata.core.ui.components.ColorBadge
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.LoadingState
import ai.labs32.khaata.core.ui.theme.KhaataShapeTokens
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.core.validation.CategoryValidator
import ai.labs32.khaata.data.repository.CategoryDeletionResult
import ai.labs32.khaata.data.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One line in the list: a category, plus whether it is indented under a parent. */
data class CategoryRow(
    val category: Category,
    val isChild: Boolean,
    val childCount: Int,
)

/** Categories grouped under their [CategoryGroup] heading. */
data class CategorySection(
    val group: CategoryGroup,
    val rows: List<CategoryRow>,
)

/** The state of the add/edit sheet. Null when the sheet is closed. */
data class CategoryEditorState(
    /** Null while creating; set while editing an existing category. */
    val id: String? = null,
    val name: String = "",
    val group: CategoryGroup = CategoryGroup.OTHER,
    val parentId: String? = null,
    val kind: CategoryKind = CategoryKind.EXPENSE,
    val iconKey: String = "category",
    val colorSeed: Int = 0,
    val isSystem: Boolean = false,
    val isArchived: Boolean = false,
    val childCount: Int = 0,
    /** Top-level categories of the same kind, which are the only legal parents. */
    val parentOptions: List<Category> = emptyList(),
    val errorCodes: Set<String> = emptySet(),
) {
    val isEditing: Boolean get() = id != null
    val canDelete: Boolean get() = isEditing && !isSystem
}

/** A delete the user has asked for and been told the consequences of, awaiting confirmation. */
data class CategoryDeleteRequest(
    val category: Category,
    val affectedTransactions: Int,
    val childCount: Int,
)

data class CategoriesUiState(
    val isLoading: Boolean = true,
    val kind: CategoryKind = CategoryKind.EXPENSE,
    val query: String = "",
    val showHidden: Boolean = false,
    val sections: List<CategorySection> = emptyList(),
    val hiddenCount: Int = 0,
    val editor: CategoryEditorState? = null,
    val deleteRequest: CategoryDeleteRequest? = null,
    val message: CategoriesMessage? = null,
)

/** A transient message for the snackbar, kept as data so it can be localised at render time. */
sealed interface CategoriesMessage {
    data object SystemCannotDelete : CategoriesMessage
    data class Deleted(val orphanedTransactions: Int) : CategoriesMessage
    data object Hidden : CategoriesMessage
    data object Shown : CategoriesMessage
}

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoriesUiState())
    val uiState: StateFlow<CategoriesUiState> = _uiState.asStateFlow()

    /** Everything in the database, including hidden rows — the UI filters, not the query. */
    private var allCategories: List<Category> = emptyList()

    init {
        categoryRepository.observeAll()
            .onEach { categories ->
                allCategories = categories
                _uiState.update { it.copy(isLoading = false).withSections(categories) }
            }
            .launchIn(viewModelScope)
    }

    fun setKind(kind: CategoryKind) =
        _uiState.update { it.copy(kind = kind).withSections(allCategories) }

    fun setQuery(query: String) =
        _uiState.update { it.copy(query = query).withSections(allCategories) }

    fun toggleShowHidden() =
        _uiState.update { it.copy(showHidden = !it.showHidden).withSections(allCategories) }

    // ---- Editor ------------------------------------------------------------------------------

    fun startCreate() {
        val kind = _uiState.value.kind
        _uiState.update {
            it.copy(
                editor = CategoryEditorState(
                    kind = kind,
                    group = if (kind == CategoryKind.INCOME) CategoryGroup.INCOME else CategoryGroup.OTHER,
                    parentOptions = parentOptionsFor(kind, excludingId = null),
                ),
            )
        }
    }

    fun startEdit(category: Category) {
        _uiState.update {
            it.copy(
                editor = CategoryEditorState(
                    id = category.id,
                    name = category.name,
                    group = category.group,
                    parentId = category.parentId,
                    kind = category.kind,
                    iconKey = category.iconKey,
                    colorSeed = category.colorSeed,
                    isSystem = category.isSystem,
                    isArchived = category.isArchived,
                    childCount = allCategories.count { child -> child.parentId == category.id },
                    parentOptions = parentOptionsFor(category.kind, excludingId = category.id),
                ),
            )
        }
    }

    fun dismissEditor() = _uiState.update { it.copy(editor = null) }

    fun editName(name: String) = updateEditor { it.copy(name = name, errorCodes = emptySet()) }

    fun editIcon(iconKey: String) = updateEditor { it.copy(iconKey = iconKey) }

    fun editColor(seed: Int) = updateEditor { it.copy(colorSeed = seed) }

    /**
     * Reparents the category being edited.
     *
     * A subcategory inherits its parent's group so it cannot end up filed under "Food" in the
     * picker and "Transport" in reports.
     */
    fun editParent(parentId: String?) = updateEditor { editor ->
        val parent = parentId?.let { id -> allCategories.firstOrNull { it.id == id } }
        editor.copy(
            parentId = parentId,
            group = parent?.group ?: editor.group,
            errorCodes = emptySet(),
        )
    }

    fun editGroup(group: CategoryGroup) = updateEditor {
        // Only meaningful for a top-level category; a child follows its parent.
        if (it.parentId != null) it else it.copy(group = group)
    }

    fun save() {
        val editor = _uiState.value.editor ?: return
        val parent = editor.parentId?.let { id -> allCategories.firstOrNull { it.id == id } }
        val siblings = allCategories
            .filter { it.parentId == editor.parentId && it.id != editor.id }
            .map { it.name }
            .toSet()

        val result = CategoryValidator.validate(
            name = editor.name,
            siblingNames = siblings,
            parentIsSubcategory = parent?.isSubcategory == true,
            hasChildren = editor.childCount > 0,
            isBecomingSubcategory = editor.parentId != null,
        )
        if (!result.isValid) {
            updateEditor { it.copy(errorCodes = result.errorsOrEmpty().map { e -> e.code }.toSet()) }
            return
        }

        viewModelScope.launch {
            if (editor.isEditing) {
                val existing = allCategories.first { it.id == editor.id }
                categoryRepository.update(
                    existing.copy(
                        name = editor.name.trim(),
                        group = editor.group,
                        parentId = editor.parentId,
                        kind = editor.kind,
                        iconKey = editor.iconKey,
                        colorSeed = editor.colorSeed,
                    ),
                )
            } else {
                categoryRepository.create(
                    name = editor.name,
                    group = editor.group,
                    parentId = editor.parentId,
                    kind = editor.kind,
                    iconKey = editor.iconKey,
                    colorSeed = editor.colorSeed,
                )
            }
            _uiState.update { it.copy(editor = null) }
        }
    }

    // ---- Hide and delete ---------------------------------------------------------------------

    /**
     * Hiding is the safe operation and is offered everywhere.
     *
     * A hidden category keeps labelling every transaction already filed under it and simply stops
     * appearing in the picker, so nothing in the user's history changes.
     */
    fun toggleHidden(category: Category) {
        viewModelScope.launch {
            categoryRepository.setArchived(category.id, !category.isArchived)
            _uiState.update {
                it.copy(
                    editor = it.editor?.copy(isArchived = !category.isArchived),
                    message = if (category.isArchived) CategoriesMessage.Shown else CategoriesMessage.Hidden,
                )
            }
        }
    }

    /**
     * Asks for confirmation, having first counted what the delete would touch.
     *
     * The count is fetched before showing the dialog rather than after deleting, because "3
     * transactions became uncategorised" is only useful as a warning, not as news.
     */
    fun requestDelete(category: Category) {
        if (category.isSystem) {
            _uiState.update { it.copy(message = CategoriesMessage.SystemCannotDelete) }
            return
        }
        viewModelScope.launch {
            val children = allCategories.filter { it.parentId == category.id }
            val affected = categoryRepository.transactionCount(category.id) +
                children.sumOf { categoryRepository.transactionCount(it.id) }
            _uiState.update {
                it.copy(
                    deleteRequest = CategoryDeleteRequest(
                        category = category,
                        affectedTransactions = affected,
                        childCount = children.size,
                    ),
                )
            }
        }
    }

    fun dismissDelete() = _uiState.update { it.copy(deleteRequest = null) }

    fun confirmDelete() {
        val request = _uiState.value.deleteRequest ?: return
        viewModelScope.launch {
            when (val result = categoryRepository.delete(request.category.id)) {
                is CategoryDeletionResult.Deleted -> _uiState.update {
                    it.copy(
                        deleteRequest = null,
                        editor = null,
                        message = CategoriesMessage.Deleted(result.orphanedTransactions),
                    )
                }

                CategoryDeletionResult.SystemCategory -> _uiState.update {
                    it.copy(deleteRequest = null, message = CategoriesMessage.SystemCannotDelete)
                }

                CategoryDeletionResult.NotFound -> _uiState.update {
                    it.copy(deleteRequest = null, editor = null)
                }
            }
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    // ---- Internals ---------------------------------------------------------------------------

    private fun updateEditor(transform: (CategoryEditorState) -> CategoryEditorState) =
        _uiState.update { state -> state.copy(editor = state.editor?.let(transform)) }

    private fun parentOptionsFor(kind: CategoryKind, excludingId: String?): List<Category> =
        allCategories
            .filter { it.parentId == null && !it.isArchived && it.id != excludingId }
            .filter { it.kind == kind || it.kind == CategoryKind.BOTH || kind == CategoryKind.BOTH }
            .sortedBy { it.name }
}

/**
 * Rebuilds the grouped list from the current filters.
 *
 * Kept as a pure function on the state so filtering never triggers a database round trip: the
 * full set is small (about a hundred rows) and already in memory, and re-querying on every
 * keystroke would make search feel laggy for no benefit.
 */
private fun CategoriesUiState.withSections(all: List<Category>): CategoriesUiState {
    val relevant = all.filter { it.kind == kind || it.kind == CategoryKind.BOTH }
    val hiddenCount = relevant.count { it.isArchived }

    val trimmedQuery = query.trim()
    val visible = relevant
        .filter { showHidden || !it.isArchived }
        .filter { trimmedQuery.isBlank() || it.name.contains(trimmedQuery, ignoreCase = true) }

    // A search that matches only a child still needs its parent shown, otherwise the result is a
    // bare indented row with nothing to indent under.
    val visibleIds = visible.map { it.id }.toSet()
    val withParents = visible + relevant.filter { parent ->
        parent.id !in visibleIds && visible.any { it.parentId == parent.id }
    }

    val childrenByParent = withParents.filter { it.parentId != null }.groupBy { it.parentId }
    val childCounts = all.groupingBy { it.parentId }.eachCount()

    val sections = withParents
        .filter { it.parentId == null }
        .groupBy { it.group }
        .toSortedMap(compareBy { it.ordinal })
        .map { (group, parents) ->
            CategorySection(
                group = group,
                rows = parents.sortedBy { it.sortOrder }.flatMap { parent ->
                    listOf(
                        CategoryRow(
                            category = parent,
                            isChild = false,
                            childCount = childCounts[parent.id] ?: 0,
                        ),
                    ) + childrenByParent[parent.id].orEmpty()
                        .sortedBy { it.sortOrder }
                        .map { CategoryRow(category = it, isChild = true, childCount = 0) }
                },
            )
        }

    return copy(sections = sections, hiddenCount = hiddenCount)
}

/**
 * The category manager.
 *
 * Two decisions shape this screen. First, hiding is offered before deleting everywhere, because a
 * deleted category takes the labels off past transactions and there is no way to get them back —
 * hiding achieves what almost everyone actually wants ("stop showing me this") with no history
 * lost. Second, the tree is fixed at two levels: the reference apps allow deeper nesting, which
 * reads as flexibility until you are three taps into a picker at a shop counter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    viewModel: CategoriesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val snackbarText = state.message?.let { snackbarTextFor(it) }
    LaunchedEffect(snackbarText) {
        if (snackbarText != null) {
            snackbarHostState.showSnackbar(snackbarText)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.categories_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (state.hiddenCount > 0) {
                        IconButton(onClick = viewModel::toggleShowHidden) {
                            Icon(
                                imageVector = if (state.showHidden) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = stringResource(
                                    if (state.showHidden) {
                                        R.string.categories_hide_hidden
                                    } else {
                                        R.string.categories_show_hidden
                                    },
                                ),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::startCreate,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.categories_add)) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            KindFilter(selected = state.kind, onSelect = viewModel::setKind)

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = { Text(stringResource(R.string.categories_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KhaataTheme.spacing.screenHorizontal),
            )

            Spacer(Modifier.height(KhaataTheme.spacing.small))

            when {
                state.isLoading -> LoadingState()

                state.sections.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Category,
                    title = stringResource(
                        if (state.query.isBlank()) {
                            R.string.categories_empty_title
                        } else {
                            R.string.categories_no_matches_title
                        },
                    ),
                    description = stringResource(
                        if (state.query.isBlank()) {
                            R.string.categories_empty_body
                        } else {
                            R.string.categories_no_matches_body
                        },
                    ),
                    actionLabel = stringResource(R.string.categories_add),
                    onAction = viewModel::startCreate,
                )

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        bottom = KhaataTheme.spacing.bottomBarClearance,
                    ),
                ) {
                    state.sections.forEach { section ->
                        item(key = "header-${section.group.name}") {
                            GroupHeader(section.group)
                        }
                        items(section.rows, key = { it.category.id }) { row ->
                            CategoryListRow(
                                row = row,
                                onClick = { viewModel.startEdit(row.category) },
                                onToggleHidden = { viewModel.toggleHidden(row.category) },
                            )
                        }
                    }
                }
            }
        }
    }

    state.editor?.let { editor ->
        CategoryEditorSheet(
            editor = editor,
            onDismiss = viewModel::dismissEditor,
            onNameChange = viewModel::editName,
            onIconChange = viewModel::editIcon,
            onColorChange = viewModel::editColor,
            onParentChange = viewModel::editParent,
            onGroupChange = viewModel::editGroup,
            onSave = viewModel::save,
            onToggleHidden = {
                editor.id?.let { id ->
                    // Reconstructed from the editor so the sheet does not need the full model.
                    viewModel.toggleHidden(
                        Category(
                            id = id,
                            name = editor.name,
                            group = editor.group,
                            parentId = editor.parentId,
                            kind = editor.kind,
                            iconKey = editor.iconKey,
                            colorSeed = editor.colorSeed,
                            isSystem = editor.isSystem,
                            isArchived = editor.isArchived,
                        ),
                    )
                }
            },
            onDelete = {
                editor.id?.let { id ->
                    viewModel.requestDelete(
                        Category(
                            id = id,
                            name = editor.name,
                            group = editor.group,
                            parentId = editor.parentId,
                            kind = editor.kind,
                            iconKey = editor.iconKey,
                            colorSeed = editor.colorSeed,
                            isSystem = editor.isSystem,
                            isArchived = editor.isArchived,
                        ),
                    )
                }
            },
        )
    }

    state.deleteRequest?.let { request ->
        DeleteConfirmDialog(
            request = request,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

@Composable
private fun KindFilter(selected: CategoryKind, onSelect: (CategoryKind) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = KhaataTheme.spacing.screenHorizontal,
                vertical = KhaataTheme.spacing.small,
            ),
        horizontalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small),
    ) {
        listOf(CategoryKind.EXPENSE, CategoryKind.INCOME).forEach { kind ->
            FilterChip(
                selected = selected == kind,
                onClick = { onSelect(kind) },
                label = {
                    Text(
                        stringResource(
                            if (kind == CategoryKind.EXPENSE) {
                                R.string.transaction_expense
                            } else {
                                R.string.transaction_income
                            },
                        ),
                    )
                },
                leadingIcon = if (selected == kind) {
                    { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun GroupHeader(group: CategoryGroup) {
    Text(
        text = groupLabel(group),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = KhaataTheme.spacing.screenHorizontal,
            end = KhaataTheme.spacing.screenHorizontal,
            top = KhaataTheme.spacing.medium,
            bottom = KhaataTheme.spacing.small,
        ),
    )
}

@Composable
private fun CategoryListRow(
    row: CategoryRow,
    onClick: () -> Unit,
    onToggleHidden: () -> Unit,
) {
    val category = row.category
    val hiddenLabel = stringResource(R.string.categories_hidden_badge)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(
                // Children are indented by the width of a badge plus its gap, which lines their
                // text up under the parent's rather than at an arbitrary offset.
                start = KhaataTheme.spacing.screenHorizontal + if (row.isChild) 24.dp else 0.dp,
                end = KhaataTheme.spacing.small,
                top = 8.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorBadge(
            icon = CategoryIcons[category.iconKey],
            colorSeed = category.colorSeed,
            size = if (row.isChild) 32.dp else 40.dp,
            // The name next to it already says what this is, so the icon is decorative.
            contentDescription = null,
            modifier = Modifier.alpha(if (category.isArchived) DIMMED_ALPHA else 1f),
        )
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = category.name,
                style = if (row.isChild) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(if (category.isArchived) DIMMED_ALPHA else 1f),
            )
            val subtitle = buildList {
                if (row.childCount > 0) {
                    add(
                        pluralStringResource(
                            R.plurals.categories_subcategory_count,
                            row.childCount,
                            row.childCount,
                        ),
                    )
                }
                if (category.isSystem) add(stringResource(R.string.categories_built_in))
            }.joinToString(" • ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Hidden is stated in words, not just by the dimmed row, so it survives being read aloud
        // and does not depend on noticing a subtle opacity difference.
        if (category.isArchived) {
            AssistChip(
                onClick = onToggleHidden,
                label = { Text(hiddenLabel, style = MaterialTheme.typography.labelSmall) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        } else {
            IconButton(onClick = onToggleHidden) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = stringResource(
                        R.string.categories_hide_named,
                        category.name,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryEditorSheet(
    editor: CategoryEditorState,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    onIconChange: (String) -> Unit,
    onColorChange: (Int) -> Unit,
    onParentChange: (String?) -> Unit,
    onGroupChange: (CategoryGroup) -> Unit,
    onSave: () -> Unit,
    onToggleHidden: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val swatchCount = KhaataTheme.money.categorySwatches.size

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = KhaataTheme.spacing.screenHorizontal)
                .padding(bottom = KhaataTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.medium),
        ) {
            Text(
                text = stringResource(
                    if (editor.isEditing) R.string.categories_edit else R.string.categories_add,
                ),
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = editor.name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.categories_name)) },
                singleLine = true,
                isError = editor.errorCodes.any { it.startsWith("name_") },
                supportingText = {
                    val code = editor.errorCodes.firstOrNull { it.startsWith("name_") }
                    if (code != null) Text(validationMessage(code))
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            // A built-in category's name and look can be changed, but not what it fundamentally
            // is, so the parent picker is hidden for system rows rather than shown and refused.
            if (!editor.isSystem) {
                ParentPicker(
                    parentId = editor.parentId,
                    options = editor.parentOptions,
                    errorCode = editor.errorCodes.firstOrNull { it.startsWith("parent_") },
                    onSelect = onParentChange,
                )

                if (editor.parentId == null) {
                    GroupPicker(
                        selected = editor.group,
                        kind = editor.kind,
                        onSelect = onGroupChange,
                    )
                }
            }

            IconPicker(selected = editor.iconKey, onSelect = onIconChange)
            ColorPicker(
                selected = editor.colorSeed,
                swatchCount = swatchCount,
                iconKey = editor.iconKey,
                onSelect = onColorChange,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (editor.isEditing) {
                    TextButton(onClick = onToggleHidden) {
                        Text(
                            stringResource(
                                if (editor.isArchived) {
                                    R.string.categories_show
                                } else {
                                    R.string.categories_hide
                                },
                            ),
                        )
                    }
                }
                if (editor.canDelete) {
                    TextButton(onClick = onDelete) {
                        Text(
                            text = stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) }
            }

            if (editor.isSystem) {
                Text(
                    text = stringResource(R.string.categories_system_cannot_delete),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ParentPicker(
    parentId: String?,
    options: List<Category>,
    errorCode: String?,
    onSelect: (String?) -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.categories_parent),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small)) {
            item {
                FilterChip(
                    selected = parentId == null,
                    onClick = { onSelect(null) },
                    label = { Text(stringResource(R.string.categories_top_level)) },
                )
            }
            items(options, key = { it.id }) { option ->
                FilterChip(
                    selected = parentId == option.id,
                    onClick = { onSelect(option.id) },
                    label = { Text(option.name) },
                )
            }
        }
        if (errorCode != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = validationMessage(errorCode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun GroupPicker(
    selected: CategoryGroup,
    kind: CategoryKind,
    onSelect: (CategoryGroup) -> Unit,
) {
    // Income categories only ever belong to the income group, so offering the spending groups
    // there would be a menu of nine wrong answers.
    val options = if (kind == CategoryKind.INCOME) {
        listOf(CategoryGroup.INCOME)
    } else {
        CategoryGroup.entries.filter { it.isSpending }
    }
    if (options.size < 2) return

    Column {
        Text(
            text = stringResource(R.string.categories_group),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small)) {
            items(options, key = { it.name }) { group ->
                FilterChip(
                    selected = selected == group,
                    onClick = { onSelect(group) },
                    label = { Text(groupLabel(group)) },
                )
            }
        }
    }
}

@Composable
private fun IconPicker(selected: String, onSelect: (String) -> Unit) {
    val chosenLabel = stringResource(R.string.categories_icon)

    Column {
        Text(
            text = chosenLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small)) {
            items(CategoryIcons.pickableKeys, key = { it }) { key ->
                val isSelected = key == selected
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(KhaataShapeTokens.card)
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable { onSelect(key) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = CategoryIcons[key],
                        // The row is a set of interchangeable choices; describing each glyph
                        // would read as noise. Selection is what a screen reader needs.
                        contentDescription = if (isSelected) chosenLabel else null,
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorPicker(
    selected: Int,
    swatchCount: Int,
    iconKey: String,
    onSelect: (Int) -> Unit,
) {
    val label = stringResource(R.string.categories_colour)
    val selectedDescription = stringResource(R.string.categories_colour_selected)

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small)) {
            itemsIndexed(List(swatchCount) { it }) { _, seed ->
                val isSelected = seed == selected
                Box(
                    Modifier
                        .size(KhaataTheme.spacing.touchTarget)
                        .clickable { onSelect(seed) }
                        // Colour alone cannot convey which swatch is chosen — a user who cannot
                        // distinguish two of them would have no way to tell. The selected one
                        // also carries a tick and a spoken label.
                        .clearAndSetSemantics {
                            if (isSelected) contentDescription = selectedDescription
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ColorBadge(
                        icon = CategoryIcons[iconKey],
                        colorSeed = seed,
                        size = 40.dp,
                        contentDescription = null,
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = KhaataTheme.money.swatch(seed),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    request: CategoryDeleteRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.categories_delete_title, request.category.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small)) {
                // The consequence is stated before the button, not after it: transactions survive
                // but lose their label, and there is no undo for that.
                Text(stringResource(R.string.categories_delete_body))
                if (request.childCount > 0) {
                    Text(
                        pluralStringResource(
                            R.plurals.categories_delete_children,
                            request.childCount,
                            request.childCount,
                        ),
                    )
                }
                if (request.affectedTransactions > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.categories_delete_orphan_warning,
                            request.affectedTransactions,
                            request.affectedTransactions,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun snackbarTextFor(message: CategoriesMessage): String = when (message) {
    CategoriesMessage.SystemCannotDelete ->
        stringResource(R.string.categories_system_cannot_delete)

    is CategoriesMessage.Deleted -> if (message.orphanedTransactions > 0) {
        pluralStringResource(
            R.plurals.categories_deleted_with_orphans,
            message.orphanedTransactions,
            message.orphanedTransactions,
        )
    } else {
        stringResource(R.string.categories_deleted)
    }

    CategoriesMessage.Hidden -> stringResource(R.string.categories_hidden_confirmation)
    CategoriesMessage.Shown -> stringResource(R.string.categories_shown_confirmation)
}

/** Maps a [CategoryValidator] error code onto a localised message. */
@Composable
private fun validationMessage(code: String): String = stringResource(
    when (code) {
        "name_required" -> R.string.validation_category_name_required
        "name_too_long" -> R.string.validation_category_name_too_long
        "name_duplicate" -> R.string.validation_category_name_duplicate
        "parent_too_deep" -> R.string.validation_category_parent_too_deep
        "parent_has_children" -> R.string.validation_category_parent_has_children
        else -> R.string.state_error_generic
    },
)

@Composable
internal fun groupLabel(group: CategoryGroup): String = stringResource(
    when (group) {
        CategoryGroup.FOOD -> R.string.category_group_food
        CategoryGroup.TRANSPORT -> R.string.category_group_transport
        CategoryGroup.BILLS -> R.string.category_group_bills
        CategoryGroup.LIFESTYLE -> R.string.category_group_lifestyle
        CategoryGroup.FINANCIAL -> R.string.category_group_financial
        CategoryGroup.FAMILY -> R.string.category_group_family
        CategoryGroup.INCOME -> R.string.category_group_income
        CategoryGroup.TRANSFER -> R.string.category_group_transfer
        CategoryGroup.OTHER -> R.string.category_group_other
    },
)

/** Enough to read a hidden row without mistaking it for an active one. */
private const val DIMMED_ALPHA = 0.5f

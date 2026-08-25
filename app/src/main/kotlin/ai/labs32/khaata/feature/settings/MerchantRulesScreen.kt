package ai.labs32.khaata.feature.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.R
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.MerchantRule
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.data.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A rule with its category resolved for display. */
data class MerchantRuleItem(
    val rule: MerchantRule,
    val categoryName: String?,
)

data class MerchantRulesUiState(
    val isLoading: Boolean = true,
    val query: String = "",
    /** Rules the user set or the app learned. The shipped starter set is counted, not listed. */
    val rules: List<MerchantRuleItem> = emptyList(),
    val seededCount: Int = 0,
    val showForgetDialog: Boolean = false,
)

@HiltViewModel
class MerchantRulesViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MerchantRulesUiState())
    val uiState: StateFlow<MerchantRulesUiState> = _uiState.asStateFlow()

    private var allItems: List<MerchantRuleItem> = emptyList()

    init {
        combine(
            categoryRepository.observeMerchantRules(),
            categoryRepository.observeAll(),
        ) { rules, categories ->
            val names = categories.associate { it.id to displayName(it, categories) }
            rules to names
        }
            .onEach { (rules, names) ->
                // The shipped set is hundreds of rows the user never chose; listing them would
                // bury the handful they actually taught the app.
                val (learned, seeded) = rules.partition { !it.isSeeded }
                allItems = learned
                    .map { MerchantRuleItem(it, names[it.categoryId]) }
                    .sortedWith(
                        compareByDescending<MerchantRuleItem> { it.rule.isUserDefined }
                            .thenByDescending { it.rule.confidence }
                            .thenBy { it.rule.merchantKey },
                    )
                _uiState.update {
                    it.copy(isLoading = false, seededCount = seeded.size).withFilter()
                }
            }
            .launchIn(viewModelScope)
    }

    private fun displayName(category: Category, all: List<Category>): String {
        val parent = category.parentId?.let { id -> all.firstOrNull { it.id == id } }
        return if (parent == null) category.name else "${parent.name} › ${category.name}"
    }

    private fun MerchantRulesUiState.withFilter(): MerchantRulesUiState {
        val trimmed = query.trim()
        return copy(
            rules = if (trimmed.isBlank()) {
                allItems
            } else {
                allItems.filter { it.rule.merchantKey.contains(trimmed, ignoreCase = true) }
            },
        )
    }

    fun setQuery(query: String) = _uiState.update { it.copy(query = query).withFilter() }

    fun delete(id: String) {
        viewModelScope.launch { categoryRepository.deleteMerchantRule(id) }
    }

    fun requestForgetAll() = _uiState.update { it.copy(showForgetDialog = true) }

    fun dismissForgetAll() = _uiState.update { it.copy(showForgetDialog = false) }

    /**
     * Clears everything learned, keeping the shipped set.
     *
     * The shipped rules stay because they are not something the app inferred about this user —
     * they are the reason a fresh install already knows Swiggy is food.
     */
    fun confirmForgetAll() {
        viewModelScope.launch {
            categoryRepository.forgetLearnedRules()
            _uiState.update { it.copy(showForgetDialog = false) }
        }
    }
}

/**
 * What the app has learned about the user's merchants.
 *
 * This screen exists because automatic categorisation is only acceptable if it is inspectable. An
 * app that quietly decides "PhonePe means Groceries" and cannot be shown or corrected is one the
 * user has to second-guess on every row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MerchantRulesScreen(
    onBack: () -> Unit,
    viewModel: MerchantRulesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.settings_merchant_rules)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Text(
                text = stringResource(R.string.merchant_rules_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = KhaataTheme.spacing.screenHorizontal,
                    vertical = KhaataTheme.spacing.small,
                ),
            )

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = { Text(stringResource(R.string.merchant_rules_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KhaataTheme.spacing.screenHorizontal),
            )

            Spacer(Modifier.height(KhaataTheme.spacing.small))

            if (state.rules.isEmpty() && !state.isLoading) {
                EmptyState(
                    icon = Icons.Outlined.Storefront,
                    title = stringResource(R.string.merchant_rules_empty_title),
                    description = stringResource(R.string.merchant_rules_empty_body),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = KhaataTheme.spacing.screenHorizontal,
                        end = KhaataTheme.spacing.screenHorizontal,
                        bottom = KhaataTheme.spacing.large,
                    ),
                    verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small),
                ) {
                    items(state.rules, key = { it.rule.id }) { item ->
                        RuleRow(item = item, onDelete = { viewModel.delete(item.rule.id) })
                    }

                    item(key = "footer") {
                        Spacer(Modifier.height(KhaataTheme.spacing.medium))
                        Text(
                            text = pluralStringResource(
                                R.plurals.merchant_rules_seeded_count,
                                state.seededCount,
                                state.seededCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(KhaataTheme.spacing.small))
                        TextButton(onClick = viewModel::requestForgetAll) {
                            Text(stringResource(R.string.settings_forget_learned))
                        }
                    }
                }
            }
        }
    }

    if (state.showForgetDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissForgetAll,
            title = { Text(stringResource(R.string.merchant_rules_forget_title)) },
            text = { Text(stringResource(R.string.merchant_rules_forget_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmForgetAll) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissForgetAll) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun RuleRow(item: MerchantRuleItem, onDelete: () -> Unit) {
    KhaataCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.rule.merchantKey,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = item.categoryName
                            ?: stringResource(R.string.categories_uncategorised),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    // Where the rule came from, in words. "You set this" and "we guessed this from
                    // four transactions" deserve different levels of trust.
                    text = if (item.rule.isUserDefined) {
                        stringResource(R.string.merchant_rules_set_by_you)
                    } else {
                        pluralStringResource(
                            R.plurals.merchant_rules_learned_from,
                            item.rule.confidence,
                            item.rule.confidence,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (item.rule.isUserDefined) {
                AssistChip(
                    onClick = onDelete,
                    label = { Text(stringResource(R.string.action_delete)) },
                )
            } else {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(
                            R.string.merchant_rules_delete_named,
                            item.rule.merchantKey,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

package ai.labs32.khaata.feature.subscription

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.labs32.khaata.R
import ai.labs32.khaata.core.entitlement.Feature
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.feature.settings.tierLabel

/**
 * The paywall.
 *
 * Two rules shape it. First, what is free is stated before what is paid: recording transactions,
 * budgets, goals, reports and exporting your own data are free forever, and a user who reads this
 * screen should come away knowing that rather than suspecting their data is hostage. Second, every
 * price shown comes from the Play Store's own localised string — the app never formats a price
 * itself, because a hardcoded "₹199" is wrong the moment there is a regional price or a sale.
 *
 * A pending purchase is treated as a first-class state rather than an error. UPI mandates can take
 * hours to clear, and a user who has paid and is told "purchase failed" is a support ticket and a
 * one-star review.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallScreen(
    onClose: () -> Unit,
    viewModel: PaywallViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message?.let { paywallMessageText(it) }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.paywall_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_close),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = KhaataTheme.spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.medium),
        ) {
            Text(
                text = stringResource(R.string.paywall_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = KhaataTheme.spacing.small),
            )

            // Deliberately above the plans, not below them in small print.
            KhaataCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    text = stringResource(R.string.paywall_free_always),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            if (state.isPending) {
                KhaataCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.HourglassEmpty,
                            contentDescription = null,
                            tint = KhaataTheme.money.warning,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(KhaataTheme.spacing.medium))
                        Text(
                            text = stringResource(R.string.paywall_pending),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            KhaataCard {
                Text(
                    text = stringResource(R.string.paywall_current_plan),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = tierLabel(state.currentTier), style = MaterialTheme.typography.titleLarge)
            }

            when {
                state.isLoading -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                // No prices means no Play Store, not a failed purchase. Said plainly, and the rest
                // of the app carries on working — nothing here is required to track money.
                state.plans.isEmpty() -> Text(
                    text = stringResource(R.string.paywall_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> state.plans.forEach { plan ->
                    PlanCard(
                        plan = plan,
                        isCurrent = plan.tier == state.currentTier,
                        onSelect = {
                            context.findActivity()?.let { activity ->
                                viewModel.purchase(activity, plan.productId)
                            }
                        },
                    )
                }
            }

            TextButton(
                onClick = viewModel::restore,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.paywall_restore)) }

            Text(
                text = stringResource(R.string.paywall_terms_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(KhaataTheme.spacing.xlarge))
        }
    }
}

@Composable
private fun PlanCard(
    plan: PaywallPlan,
    isCurrent: Boolean,
    onSelect: () -> Unit,
) {
    KhaataCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text = tierLabel(plan.tier), style = MaterialTheme.typography.titleLarge)
                Text(
                    // The store's own price string, never one the app assembled.
                    text = plan.formattedPrice,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            plan.freeTrialDays?.let { days ->
                Text(
                    text = pluralStringResource(R.plurals.paywall_free_trial, days, days),
                    style = MaterialTheme.typography.labelMedium,
                    color = KhaataTheme.money.income,
                )
            }
        }

        Spacer(Modifier.height(KhaataTheme.spacing.medium))

        plan.features.forEach { feature ->
            Row(
                Modifier.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = KhaataTheme.money.income,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(KhaataTheme.spacing.small))
                Text(text = featureLabel(feature), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(KhaataTheme.spacing.medium))

        if (isCurrent) {
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.paywall_current_plan)) }
        } else {
            Button(
                onClick = onSelect,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.paywall_choose)) }
        }
    }
}

@Composable
private fun featureLabel(feature: Feature): String = stringResource(
    when (feature) {
        Feature.AD_FREE -> R.string.paywall_feature_ad_free
        Feature.UNLIMITED_ACCOUNTS -> R.string.paywall_feature_unlimited_accounts
        Feature.ADVANCED_REPORTS -> R.string.paywall_feature_advanced_reports
        Feature.CUSTOM_DATE_RANGES -> R.string.paywall_feature_custom_ranges
        Feature.RECEIPT_ATTACHMENTS -> R.string.paywall_feature_receipts
        Feature.SCHEDULED_BACKUP -> R.string.paywall_feature_scheduled_backup
        Feature.BUDGET_ROLLOVER -> R.string.paywall_feature_rollover
        Feature.DASHBOARD_CUSTOMISATION -> R.string.paywall_feature_dashboard
        Feature.CLOUD_AI_ASSISTANT -> R.string.paywall_feature_ai_assistant
        Feature.AI_ENHANCED_INSIGHTS -> R.string.paywall_feature_ai_insights
        Feature.AI_SMART_CATEGORISATION -> R.string.paywall_feature_ai_categorisation
        Feature.SHARED_HOUSEHOLD -> R.string.paywall_feature_shared
        Feature.FAMILY_BUDGETS -> R.string.paywall_feature_family_budgets
        Feature.SHARED_GOALS -> R.string.paywall_feature_shared_goals

        // Free features are never listed as a reason to pay.
        Feature.UNLIMITED_TRANSACTIONS,
        Feature.BASIC_REPORTS,
        Feature.BUDGETS,
        Feature.GOALS,
        Feature.RULE_BASED_INSIGHTS,
        Feature.CSV_EXPORT,
        Feature.JSON_BACKUP,
        Feature.NATURAL_LANGUAGE_ENTRY,
        Feature.BIOMETRIC_LOCK,
        -> R.string.paywall_feature_included
    },
)

@Composable
private fun paywallMessageText(message: PaywallMessage): String = stringResource(
    when (message) {
        PaywallMessage.PurchaseCompleted -> R.string.paywall_purchase_done
        PaywallMessage.PurchasePending -> R.string.paywall_pending
        PaywallMessage.PurchaseFailed -> R.string.paywall_purchase_failed
        PaywallMessage.RestoredNothing -> R.string.paywall_restore_none
        PaywallMessage.Restored -> R.string.paywall_restore_done
    },
)

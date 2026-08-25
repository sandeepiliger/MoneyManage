package ai.labs32.khaata.feature.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.labs32.khaata.R
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.SettingsRow
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.navigation.Routes

/**
 * The "More" tab.
 *
 * Everything that did not earn a bottom-navigation slot, grouped by what it is for rather than
 * alphabetically. Money products (cards, loans, investments) sit together because someone
 * checking one usually checks the others.
 */
@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    val spacing = KhaataTheme.spacing

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.screenHorizontal,
            end = spacing.screenHorizontal,
            top = spacing.default,
            bottom = spacing.bottomBarClearance,
        ),
    ) {
        item {
            Text(
                text = stringResource(R.string.more_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = spacing.default),
            )
        }

        item {
            MoreGroup {
                SettingsRow(
                    title = stringResource(R.string.reports_title),
                    icon = Icons.AutoMirrored.Outlined.ShowChart,
                    onClick = { onNavigate(Routes.REPORTS) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    title = stringResource(R.string.ai_title),
                    subtitle = stringResource(R.string.ai_source_on_device),
                    icon = Icons.Outlined.AutoAwesome,
                    onClick = { onNavigate(Routes.AI_ASSISTANT) },
                )
            }
        }

        item { Spacer(Modifier.height(spacing.medium)) }

        item {
            MoreGroup {
                SettingsRow(
                    title = stringResource(R.string.accounts_title),
                    icon = Icons.Outlined.AccountBalanceWallet,
                    onClick = { onNavigate(Routes.ACCOUNTS) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    title = stringResource(R.string.cards_title),
                    icon = Icons.Outlined.CreditCard,
                    onClick = { onNavigate(Routes.CREDIT_CARDS) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    title = stringResource(R.string.loans_title),
                    icon = Icons.Outlined.AccountBalance,
                    onClick = { onNavigate(Routes.LOANS) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    title = stringResource(R.string.investments_title),
                    icon = Icons.Outlined.TrendingUp,
                    onClick = { onNavigate(Routes.INVESTMENTS) },
                )
            }
        }

        item { Spacer(Modifier.height(spacing.medium)) }

        item {
            MoreGroup {
                SettingsRow(
                    title = stringResource(R.string.goals_title),
                    icon = Icons.Outlined.Flag,
                    onClick = { onNavigate(Routes.GOALS) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    title = stringResource(R.string.recurring_title),
                    icon = Icons.Outlined.EventRepeat,
                    onClick = { onNavigate(Routes.RECURRING) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    title = stringResource(R.string.subscriptions_title),
                    icon = Icons.Outlined.Subscriptions,
                    onClick = { onNavigate(Routes.SUBSCRIPTIONS) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    title = stringResource(R.string.categories_title),
                    icon = Icons.Outlined.Category,
                    onClick = { onNavigate(Routes.CATEGORIES) },
                )
            }
        }

        item { Spacer(Modifier.height(spacing.medium)) }

        item {
            MoreGroup {
                // Privacy is given its own top-level entry rather than being buried three levels
                // into settings. It is the reason a lot of people choose an app like this.
                SettingsRow(
                    title = stringResource(R.string.privacy_title),
                    subtitle = stringResource(R.string.privacy_summary),
                    icon = Icons.Outlined.Lock,
                    onClick = { onNavigate(Routes.PRIVACY) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    title = stringResource(R.string.settings_subscription),
                    icon = Icons.Outlined.WorkspacePremium,
                    onClick = { onNavigate(Routes.PAYWALL) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    title = stringResource(R.string.settings_title),
                    icon = Icons.Outlined.Settings,
                    onClick = { onNavigate(Routes.SETTINGS) },
                )
            }
        }
    }
}

@Composable
private fun MoreGroup(content: @Composable () -> Unit) {
    KhaataCard(contentPadding = PaddingValues(vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

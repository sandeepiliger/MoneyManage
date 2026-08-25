package ai.labs32.khaata.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import ai.labs32.khaata.feature.accounts.AccountDetailScreen
import ai.labs32.khaata.feature.accounts.AccountEditScreen
import ai.labs32.khaata.feature.accounts.AccountsScreen
import ai.labs32.khaata.feature.ai.AiAssistantScreen
import ai.labs32.khaata.feature.budgets.BudgetDetailScreen
import ai.labs32.khaata.feature.budgets.BudgetEditScreen
import ai.labs32.khaata.feature.budgets.BudgetsScreen
import ai.labs32.khaata.feature.categories.CategoriesScreen
import ai.labs32.khaata.feature.creditcards.CreditCardsScreen
import ai.labs32.khaata.feature.dashboard.DashboardScreen
import ai.labs32.khaata.feature.goals.GoalsScreen
import ai.labs32.khaata.feature.insights.InsightsScreen
import ai.labs32.khaata.feature.investments.InvestmentsScreen
import ai.labs32.khaata.feature.loans.LoanDetailScreen
import ai.labs32.khaata.feature.loans.LoansScreen
import ai.labs32.khaata.feature.more.MoreScreen
import ai.labs32.khaata.feature.recurring.RecurringScreen
import ai.labs32.khaata.feature.reports.ReportsScreen
import ai.labs32.khaata.feature.settings.AboutScreen
import ai.labs32.khaata.feature.settings.BackupScreen
import ai.labs32.khaata.feature.settings.MerchantRulesScreen
import ai.labs32.khaata.feature.settings.PrivacyDashboardScreen
import ai.labs32.khaata.feature.settings.SettingsScreen
import ai.labs32.khaata.feature.subscription.PaywallScreen
import ai.labs32.khaata.feature.subscriptions.SubscriptionsScreen
import ai.labs32.khaata.feature.transactions.NaturalLanguageEntryScreen
import ai.labs32.khaata.feature.transactions.PendingImportsScreen
import ai.labs32.khaata.feature.transactions.RecentlyDeletedScreen
import ai.labs32.khaata.feature.transactions.TransactionEditScreen
import ai.labs32.khaata.feature.transactions.TransactionsScreen

/**
 * The app's navigation graph.
 *
 * Transitions are deliberately quick and shallow. A finance app is used in short bursts, often
 * one-handed while doing something else, and a 300ms flourish between screens is friction the
 * hundredth time someone records a chai.
 */
@Composable
fun KhaataNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { slideIn() },
        exitTransition = { fadeOut(tween(TRANSITION_MS)) },
        popEnterTransition = { fadeIn(tween(TRANSITION_MS)) },
        popExitTransition = { slideOut() },
    ) {
        // ---- Top-level tabs ------------------------------------------------------------------

        composable(Routes.HOME) {
            DashboardScreen(
                onNavigate = navController::navigate,
                onAddTransaction = { navController.navigate(Routes.ADD_TRANSACTION) },
                onOpenTransaction = { navController.navigate(Routes.transactionDetail(it)) },
            )
        }

        composable(Routes.TRANSACTIONS) {
            TransactionsScreen(
                onOpenTransaction = { navController.navigate(Routes.transactionDetail(it)) },
                onAddTransaction = { navController.navigate(Routes.ADD_TRANSACTION) },
            )
        }

        composable(Routes.BUDGETS) {
            BudgetsScreen(
                onOpenBudget = { navController.navigate(Routes.budgetDetail(it)) },
                onAddBudget = { navController.navigate(Routes.ADD_BUDGET) },
            )
        }

        composable(Routes.INSIGHTS) {
            InsightsScreen(
                onOpenAssistant = { navController.navigate(Routes.AI_ASSISTANT) },
                onOpenReports = { navController.navigate(Routes.REPORTS) },
                onOpenBudget = { navController.navigate(Routes.budgetDetail(it)) },
            )
        }

        composable(Routes.MORE) {
            MoreScreen(onNavigate = navController::navigate)
        }

        // ---- Transactions --------------------------------------------------------------------

        composable(Routes.ADD_TRANSACTION) {
            TransactionEditScreen(
                transactionId = null,
                onDone = { navController.popBackStack() },
                onDescribeInstead = { navController.navigate(Routes.NATURAL_LANGUAGE_ENTRY) },
            )
        }

        composable(
            route = Routes.EDIT_TRANSACTION,
            arguments = listOf(navArgument(Routes.Args.TRANSACTION_ID) { type = NavType.StringType }),
        ) { entry ->
            TransactionEditScreen(
                transactionId = entry.arguments?.getString(Routes.Args.TRANSACTION_ID),
                onDone = { navController.popBackStack() },
                onDescribeInstead = null,
            )
        }

        composable(
            route = Routes.TRANSACTION_DETAIL,
            arguments = listOf(navArgument(Routes.Args.TRANSACTION_ID) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(Routes.Args.TRANSACTION_ID).orEmpty()
            ai.labs32.khaata.feature.transactions.TransactionDetailScreen(
                transactionId = id,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(Routes.editTransaction(id)) },
            )
        }

        composable(Routes.NATURAL_LANGUAGE_ENTRY) {
            NaturalLanguageEntryScreen(onDone = { navController.popBackStack() })
        }

        composable(Routes.PENDING_IMPORTS) {
            PendingImportsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.RECENTLY_DELETED) {
            RecentlyDeletedScreen(onBack = { navController.popBackStack() })
        }

        // ---- Accounts ------------------------------------------------------------------------

        composable(Routes.ACCOUNTS) {
            AccountsScreen(
                onOpenAccount = { navController.navigate(Routes.accountDetail(it)) },
                onAddAccount = { navController.navigate(Routes.ADD_ACCOUNT) },
                onUpgrade = { navController.navigate(Routes.PAYWALL) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.ADD_ACCOUNT) {
            AccountEditScreen(accountId = null, onDone = { navController.popBackStack() })
        }

        composable(
            route = Routes.EDIT_ACCOUNT,
            arguments = listOf(navArgument(Routes.Args.ACCOUNT_ID) { type = NavType.StringType }),
        ) { entry ->
            AccountEditScreen(
                accountId = entry.arguments?.getString(Routes.Args.ACCOUNT_ID),
                onDone = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.ACCOUNT_DETAIL,
            arguments = listOf(navArgument(Routes.Args.ACCOUNT_ID) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(Routes.Args.ACCOUNT_ID).orEmpty()
            AccountDetailScreen(
                accountId = id,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(Routes.editAccount(id)) },
                onOpenTransaction = { navController.navigate(Routes.transactionDetail(it)) },
            )
        }

        // ---- Budgets -------------------------------------------------------------------------

        composable(Routes.ADD_BUDGET) {
            BudgetEditScreen(budgetId = null, onDone = { navController.popBackStack() })
        }

        composable(
            route = Routes.EDIT_BUDGET,
            arguments = listOf(navArgument(Routes.Args.BUDGET_ID) { type = NavType.StringType }),
        ) { entry ->
            BudgetEditScreen(
                budgetId = entry.arguments?.getString(Routes.Args.BUDGET_ID),
                onDone = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.BUDGET_DETAIL,
            arguments = listOf(navArgument(Routes.Args.BUDGET_ID) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(Routes.Args.BUDGET_ID).orEmpty()
            BudgetDetailScreen(
                budgetId = id,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(Routes.editBudget(id)) },
                onOpenTransaction = { navController.navigate(Routes.transactionDetail(it)) },
            )
        }

        // ---- Categories ----------------------------------------------------------------------

        composable(Routes.CATEGORIES) {
            CategoriesScreen(onBack = { navController.popBackStack() })
        }

        // ---- Recurring and subscriptions -----------------------------------------------------

        composable(Routes.RECURRING) {
            RecurringScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SUBSCRIPTIONS) {
            SubscriptionsScreen(onBack = { navController.popBackStack() })
        }

        // ---- Products ------------------------------------------------------------------------

        composable(Routes.CREDIT_CARDS) {
            CreditCardsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.LOANS) {
            LoansScreen(
                onBack = { navController.popBackStack() },
                onOpenLoan = { navController.navigate(Routes.loanDetail(it)) },
            )
        }

        composable(
            route = Routes.LOAN_DETAIL,
            arguments = listOf(navArgument(Routes.Args.LOAN_ID) { type = NavType.StringType }),
        ) { entry ->
            LoanDetailScreen(
                loanId = entry.arguments?.getString(Routes.Args.LOAN_ID).orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.INVESTMENTS) {
            InvestmentsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.GOALS) {
            GoalsScreen(onBack = { navController.popBackStack() })
        }

        // ---- Reports and assistant -----------------------------------------------------------

        composable(Routes.REPORTS) {
            ReportsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.AI_ASSISTANT) {
            AiAssistantScreen(
                onBack = { navController.popBackStack() },
                onUpgrade = { navController.navigate(Routes.PAYWALL) },
            )
        }

        // ---- Settings ------------------------------------------------------------------------

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigate = navController::navigate,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.PRIVACY) {
            PrivacyDashboardScreen(
                onBack = { navController.popBackStack() },
                onOpenBackup = { navController.navigate(Routes.BACKUP) },
            )
        }

        composable(Routes.BACKUP) {
            BackupScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.MERCHANT_RULES) {
            MerchantRulesScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.PAYWALL) {
            PaywallScreen(onClose = { navController.popBackStack() })
        }
    }
}

private fun AnimatedContentTransitionScope<*>.slideIn() =
    slideInHorizontally(tween(TRANSITION_MS)) { it / 6 } + fadeIn(tween(TRANSITION_MS))

private fun AnimatedContentTransitionScope<*>.slideOut() =
    slideOutHorizontally(tween(TRANSITION_MS)) { it / 6 } + fadeOut(tween(TRANSITION_MS))

/** Short enough to feel instant, long enough not to look like a jump cut. */
private const val TRANSITION_MS = 180

package ai.labs32.khaata.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.ui.graphics.vector.ImageVector
import ai.labs32.khaata.R

/**
 * Every destination in the app.
 *
 * Routes are string constants with typed argument helpers rather than free-form strings at call
 * sites, so a renamed route breaks at compile time instead of at runtime on a screen nobody
 * tested.
 */
object Routes {

    // Top-level tabs
    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val BUDGETS = "budgets"
    const val INSIGHTS = "insights"
    const val MORE = "more"

    // Entry
    const val ONBOARDING = "onboarding"
    const val LOCK = "lock"

    // Transaction flows
    const val ADD_TRANSACTION = "transaction/add"
    const val NATURAL_LANGUAGE_ENTRY = "transaction/describe"
    const val PENDING_IMPORTS = "transaction/pending"
    const val RECENTLY_DELETED = "transaction/deleted"

    private const val TRANSACTION_DETAIL_BASE = "transaction/detail"
    const val TRANSACTION_DETAIL = "$TRANSACTION_DETAIL_BASE/{transactionId}"
    fun transactionDetail(transactionId: String) = "$TRANSACTION_DETAIL_BASE/$transactionId"

    private const val EDIT_TRANSACTION_BASE = "transaction/edit"
    const val EDIT_TRANSACTION = "$EDIT_TRANSACTION_BASE/{transactionId}"
    fun editTransaction(transactionId: String) = "$EDIT_TRANSACTION_BASE/$transactionId"

    // Accounts
    const val ACCOUNTS = "accounts"
    const val ADD_ACCOUNT = "accounts/add"

    private const val ACCOUNT_DETAIL_BASE = "accounts/detail"
    const val ACCOUNT_DETAIL = "$ACCOUNT_DETAIL_BASE/{accountId}"
    fun accountDetail(accountId: String) = "$ACCOUNT_DETAIL_BASE/$accountId"

    private const val EDIT_ACCOUNT_BASE = "accounts/edit"
    const val EDIT_ACCOUNT = "$EDIT_ACCOUNT_BASE/{accountId}"
    fun editAccount(accountId: String) = "$EDIT_ACCOUNT_BASE/$accountId"

    // Budgets
    const val ADD_BUDGET = "budgets/add"

    private const val BUDGET_DETAIL_BASE = "budgets/detail"
    const val BUDGET_DETAIL = "$BUDGET_DETAIL_BASE/{budgetId}"
    fun budgetDetail(budgetId: String) = "$BUDGET_DETAIL_BASE/$budgetId"

    private const val EDIT_BUDGET_BASE = "budgets/edit"
    const val EDIT_BUDGET = "$EDIT_BUDGET_BASE/{budgetId}"
    fun editBudget(budgetId: String) = "$EDIT_BUDGET_BASE/$budgetId"

    // Categories
    const val CATEGORIES = "categories"
    const val ADD_CATEGORY = "categories/add"

    // Recurring and subscriptions
    const val RECURRING = "recurring"
    const val ADD_RECURRING = "recurring/add"
    const val SUBSCRIPTIONS = "subscriptions"
    const val ADD_SUBSCRIPTION = "subscriptions/add"

    // Products
    const val CREDIT_CARDS = "cards"
    const val ADD_CREDIT_CARD = "cards/add"

    private const val CARD_DETAIL_BASE = "cards/detail"
    const val CREDIT_CARD_DETAIL = "$CARD_DETAIL_BASE/{cardId}"
    fun creditCardDetail(cardId: String) = "$CARD_DETAIL_BASE/$cardId"

    const val LOANS = "loans"
    const val ADD_LOAN = "loans/add"

    private const val LOAN_DETAIL_BASE = "loans/detail"
    const val LOAN_DETAIL = "$LOAN_DETAIL_BASE/{loanId}"
    fun loanDetail(loanId: String) = "$LOAN_DETAIL_BASE/$loanId"

    const val INVESTMENTS = "investments"
    const val ADD_INVESTMENT = "investments/add"

    const val GOALS = "goals"
    const val ADD_GOAL = "goals/add"

    private const val GOAL_DETAIL_BASE = "goals/detail"
    const val GOAL_DETAIL = "$GOAL_DETAIL_BASE/{goalId}"
    fun goalDetail(goalId: String) = "$GOAL_DETAIL_BASE/$goalId"

    // Reports and assistant
    const val REPORTS = "reports"
    const val AI_ASSISTANT = "assistant"

    // Settings
    const val SETTINGS = "settings"
    const val PRIVACY = "settings/privacy"
    const val BACKUP = "settings/backup"
    const val AI_SETTINGS = "settings/ai"
    const val SECURITY_SETTINGS = "settings/security"
    const val NOTIFICATION_SETTINGS = "settings/notifications"
    const val MERCHANT_RULES = "settings/merchants"
    const val ABOUT = "settings/about"
    const val PAYWALL = "paywall"

    /** Argument keys, so a typo in a route argument is caught in one place. */
    object Args {
        const val TRANSACTION_ID = "transactionId"
        const val ACCOUNT_ID = "accountId"
        const val BUDGET_ID = "budgetId"
        const val CARD_ID = "cardId"
        const val LOAN_ID = "loanId"
        const val GOAL_ID = "goalId"
    }
}

/**
 * The bottom navigation bar.
 *
 * Five destinations, which is the practical maximum before labels start truncating and targets
 * get too small. Reports and the assistant live under More rather than competing for a slot:
 * they are things people visit occasionally, while the four that made the cut are what someone
 * opens the app to do.
 */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME(
        route = Routes.HOME,
        labelRes = R.string.nav_home,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    TRANSACTIONS(
        route = Routes.TRANSACTIONS,
        labelRes = R.string.nav_transactions,
        selectedIcon = Icons.AutoMirrored.Filled.ListAlt,
        unselectedIcon = Icons.AutoMirrored.Outlined.ListAlt,
    ),
    BUDGETS(
        route = Routes.BUDGETS,
        labelRes = R.string.nav_budgets,
        selectedIcon = Icons.Filled.PieChart,
        unselectedIcon = Icons.Outlined.PieChart,
    ),
    INSIGHTS(
        route = Routes.INSIGHTS,
        labelRes = R.string.nav_insights,
        selectedIcon = Icons.Filled.Lightbulb,
        unselectedIcon = Icons.Outlined.Lightbulb,
    ),
    MORE(
        route = Routes.MORE,
        labelRes = R.string.nav_more,
        selectedIcon = Icons.Filled.MoreHoriz,
        unselectedIcon = Icons.Outlined.MoreHoriz,
    ),
    ;

    companion object {
        fun fromRoute(route: String?): TopLevelDestination? =
            entries.firstOrNull { it.route == route }
    }
}

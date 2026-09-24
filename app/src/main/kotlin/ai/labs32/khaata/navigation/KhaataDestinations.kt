package ai.labs32.khaata.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.Home
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
    /** The Activity tab: the transaction list, with the reports behind its Analysis switch. */
    const val TRANSACTIONS = "transactions"
    /** Budgets, bills and goals -- everything about the rest of the month. */
    const val PLAN = "plan"
    /** Net worth and everything it is made of: accounts, cards, loans, investments. */
    const val MONEY = "money"

    /** Pushed from Home's insight card and from Activity's Analysis view; no longer a tab. */
    const val INSIGHTS = "insights"

    // Entry
    const val ONBOARDING = "onboarding"
    const val LOCK = "lock"

    // Transaction flows
    const val ADD_TRANSACTION = "transaction/add"
    private const val NATURAL_LANGUAGE_ENTRY_BASE = "transaction/describe"

    /**
     * Describe-a-spend entry.
     *
     * `listen` decides whether the screen opens the speech recogniser itself. The microphone
     * button means "start talking now", so it arrives with listen=true and the user speaks
     * without a second tap; "describe instead" on the manual entry screen arrives with false,
     * because that user has already chosen to type.
     */
    const val NATURAL_LANGUAGE_ENTRY = "$NATURAL_LANGUAGE_ENTRY_BASE?listen={listen}"

    fun naturalLanguageEntry(listen: Boolean = false) =
        "$NATURAL_LANGUAGE_ENTRY_BASE?listen=$listen"
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

    private const val LOAN_EDIT_BASE = "loans/edit"
    const val EDIT_LOAN = "$LOAN_EDIT_BASE/{loanId}"
    fun editLoan(loanId: String) = "$LOAN_EDIT_BASE/$loanId"

    private const val LOAN_DETAIL_BASE = "loans/detail"
    const val LOAN_DETAIL = "$LOAN_DETAIL_BASE/{loanId}"
    fun loanDetail(loanId: String) = "$LOAN_DETAIL_BASE/$loanId"

    const val INVESTMENTS = "investments"
    const val ADD_INVESTMENT = "investments/add"

    private const val INVESTMENT_DETAIL_BASE = "investments/detail"
    const val INVESTMENT_DETAIL = "$INVESTMENT_DETAIL_BASE/{investmentId}"
    fun investmentDetail(investmentId: String) = "$INVESTMENT_DETAIL_BASE/$investmentId"

    const val GOALS = "goals"
    const val ADD_GOAL = "goals/add"

    /** Reordering and hiding dashboard cards. */
    const val DASHBOARD_CUSTOMISE = "settings/dashboard"

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
        const val INVESTMENT_ID = "investmentId"
        const val LISTEN = "listen"
    }
}

/**
 * The bottom navigation bar's destinations.
 *
 * Four, split two either side of the add button, each answering one question: what needs me
 * today (Home), where did it go (Activity), what is coming (Plan) and what do I have (Money).
 *
 * This replaced Home, Transactions, Budgets, Insights and a More tab of thirteen entries, six of
 * which Home also repeated as shortcut tiles. Reports now sit behind Activity's Analysis switch,
 * bills and goals sit beside budgets, accounts and the money products share one tab, and settings
 * open from the avatar on Home -- so nothing needs a catch-all tab to be found.
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
    ACTIVITY(
        route = Routes.TRANSACTIONS,
        labelRes = R.string.nav_activity,
        selectedIcon = Icons.AutoMirrored.Filled.ReceiptLong,
        unselectedIcon = Icons.AutoMirrored.Outlined.ReceiptLong,
    ),
    PLAN(
        route = Routes.PLAN,
        labelRes = R.string.nav_plan,
        selectedIcon = Icons.Filled.DonutLarge,
        unselectedIcon = Icons.Outlined.DonutLarge,
    ),
    MONEY(
        route = Routes.MONEY,
        labelRes = R.string.nav_money,
        selectedIcon = Icons.Filled.AccountBalanceWallet,
        unselectedIcon = Icons.Outlined.AccountBalanceWallet,
    ),
    ;

    companion object {
        fun fromRoute(route: String?): TopLevelDestination? =
            entries.firstOrNull { it.route == route }

        /** The two tabs drawn left of the add button; the rest go to its right. */
        const val LEADING_COUNT = 2
    }
}

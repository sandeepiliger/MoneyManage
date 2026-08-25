package ai.labs32.khaata.data.demo

import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.demo.DemoDataGenerator
import ai.labs32.khaata.core.demo.DemoDataset
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.BudgetRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.CreditCardRepository
import ai.labs32.khaata.data.repository.GoalRepository
import ai.labs32.khaata.data.repository.InvestmentRepository
import ai.labs32.khaata.data.repository.LoanRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.data.repository.RecurringRepository
import ai.labs32.khaata.data.repository.SubscriptionRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads and removes the sample dataset.
 *
 * One implementation, used by both onboarding and settings. Two copies of "write demo data across
 * nine repositories" would eventually disagree about which one of them to write, and the copy that
 * forgot a repository would leave orphan rows behind on clear.
 *
 * Every record it writes carries a `demo-` id prefix, which is what the `deleteDemoData` queries
 * key off. That prefix is the only thing separating sample figures from real ones, so nothing here
 * writes a record without it — the generator owns that invariant and this class never edits ids.
 */
@Singleton
class DemoDataManager @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val budgetRepository: BudgetRepository,
    private val recurringRepository: RecurringRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val creditCardRepository: CreditCardRepository,
    private val loanRepository: LoanRepository,
    private val investmentRepository: InvestmentRepository,
    private val goalRepository: GoalRepository,
    private val profileRepository: ProfileRepository,
    private val clock: KhaataClock,
) {

    /**
     * Writes the sample dataset and marks the profile as being in demo mode.
     *
     * @return how many records were written.
     */
    suspend fun load(currency: CurrencyCode = CurrencyCode.DEFAULT): Int {
        val dataset: DemoDataset = DemoDataGenerator(currency = currency).generate(clock.today())

        categoryRepository.seedIfEmpty()
        accountRepository.upsertAll(dataset.accounts)
        transactionRepository.createAll(dataset.transactions)
        budgetRepository.upsertAll(dataset.budgets)
        recurringRepository.upsertAll(dataset.recurringRules)
        subscriptionRepository.upsertAll(dataset.subscriptions)
        creditCardRepository.upsertAll(dataset.creditCards)
        loanRepository.upsertAll(dataset.loans)
        investmentRepository.upsertAll(dataset.investments)
        goalRepository.upsertAll(dataset.goals)

        profileRepository.setDemoMode(true)
        return dataset.totalRecords
    }

    /**
     * Removes every demo record, leaving anything the user entered themselves alone.
     *
     * Accounts go last: a demo transaction references a demo account, and deleting the account
     * first would trip the foreign key. Categories are untouched — they are shared with the user's
     * own data and are not demo records.
     */
    suspend fun clear() {
        transactionRepository.deleteDemoData()
        budgetRepository.deleteDemoData()
        recurringRepository.deleteDemoData()
        subscriptionRepository.deleteDemoData()
        creditCardRepository.deleteDemoData()
        loanRepository.deleteDemoData()
        investmentRepository.deleteDemoData()
        goalRepository.deleteDemoData()
        accountRepository.deleteDemoData()

        profileRepository.setDemoMode(false)
    }
}

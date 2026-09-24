package ai.labs32.khaata.core.sms

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ai.labs32.khaata.core.calc.BalanceCalculator
import ai.labs32.khaata.core.categorize.MerchantCategorizer
import ai.labs32.khaata.core.common.DateRange
import ai.labs32.khaata.core.common.FixedKhaataClock
import ai.labs32.khaata.core.database.KhaataDatabase
import ai.labs32.khaata.core.database.toEntity
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.model.AppSettings
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.TransactionSource
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.data.repository.SettingsRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/**
 * Bank messages added straight to the ledger, end to end through the real importer and a real
 * database.
 *
 * Adding without asking is only safe because of what happens before the write, so that is what
 * these pin: a live message counts at once, the history read still waits for review, the two
 * messages of a transfer still become one transfer (the first leg is no longer pending when the
 * second arrives), and neither a manual entry nor a row the user deleted comes back as a second
 * copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsAutoAddTest {

    private lateinit var database: KhaataDatabase
    private lateinit var transactions: TransactionRepository
    private lateinit var importer: SmsTransactionImporter
    private val settings = mockk<SettingsRepository>()
    private var autoAdd = true

    private val clock = FixedKhaataClock(Instant.parse("2026-03-05T06:30:00Z"))
    private val day = LocalDate.of(2026, 3, 5)

    private val hdfc = Account(
        id = "acc-hdfc",
        name = "HDFC",
        type = AccountType.BANK,
        openingBalance = Money.of("50000"),
        maskedIdentifier = "4821",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
    private val icici = Account(
        id = "acc-icici",
        name = "ICICI",
        type = AccountType.BANK,
        openingBalance = Money.of("10000"),
        maskedIdentifier = "1190",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KhaataDatabase::class.java,
        ).allowMainThreadQueries().build()
        val categorizer = MerchantCategorizer()
        transactions = TransactionRepository(
            database.transactionDao(),
            database.merchantRuleDao(),
            categorizer,
            clock,
        )
        coEvery { settings.current() } answers {
            AppSettings(smsImportEnabled = true, smsAutoAdd = autoAdd)
        }
        importer = SmsTransactionImporter(
            settingsRepository = settings,
            profileRepository = ProfileRepository(database.userProfileDao(), clock),
            accountRepository = AccountRepository(database.accountDao(), database.transactionDao(), clock),
            categoryRepository = CategoryRepository(
                database.categoryDao(),
                database.merchantRuleDao(),
                database.transactionDao(),
                categorizer,
            ),
            transactionRepository = transactions,
            clock = clock,
        )
        database.accountDao().upsertAll(listOf(hdfc, icici).map { it.toEntity() })
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `a live message is added to the ledger and counts at once`() = runTest {
        val outcome = importer.import(SPEND_250, sender = "SBIUPI")

        assertThat(outcome).isInstanceOf(SmsImportOutcome.Staged::class.java)
        assertThat((outcome as SmsImportOutcome.Staged).autoAdded).isTrue()
        val row = all().single()
        assertThat(row.isPending).isFalse()
        assertThat(row.source).isEqualTo(TransactionSource.SMS_IMPORT)
        assertThat(balanceOf(hdfc)).isEqualTo(Money.of("49750"))
    }

    @Test
    fun `with automatic adding off, a message waits for review`() = runTest {
        autoAdd = false
        val outcome = importer.import(SPEND_250, sender = "SBIUPI") as SmsImportOutcome.Staged

        assertThat(outcome.autoAdded).isFalse()
        assertThat(all().single().isPending).isTrue()
        assertThat(balanceOf(hdfc)).isEqualTo(Money.of("50000"))
    }

    /** History can overlap a balance the user typed in, so the inbox read is always reviewed. */
    @Test
    fun `the inbox read of past messages always waits for review`() = runTest {
        val outcome = importer.import(SPEND_250, sender = "SBIUPI", receivedOn = day, fromInboxScan = true)
            as SmsImportOutcome.Staged

        assertThat(outcome.autoAdded).isFalse()
        assertThat(all().single().isPending).isTrue()
    }

    /**
     * The debit is added the moment it arrives, so the credit finds it already in the ledger. It
     * must still become one transfer, or the move would count as ₹5,000 spent and ₹5,000 earned.
     */
    @Test
    fun `the two messages of a transfer become one transfer when the first was added`() = runTest {
        importer.import(TRANSFER_OUT, sender = "SBIUPI")
        val second = importer.import(TRANSFER_IN, sender = "SBIINB")

        assertThat(second).isInstanceOf(SmsImportOutcome.PairedAsTransfer::class.java)
        val row = all().single()
        assertThat(row.type).isEqualTo(TransactionType.TRANSFER)
        assertThat(row.isPending).isFalse()
        assertThat(row.accountId).isEqualTo(hdfc.id)
        assertThat(row.transferAccountId).isEqualTo(icici.id)
        assertThat(row.countsAsSpending).isFalse()
        assertThat(row.countsAsIncome).isFalse()
        assertThat(balanceOf(hdfc)).isEqualTo(Money.of("45000"))
        assertThat(balanceOf(icici)).isEqualTo(Money.of("15000"))
    }

    /** Once the user has changed the first leg it is theirs; the second message stays separate. */
    @Test
    fun `a first leg the user has edited is not rewritten into a transfer`() = runTest {
        importer.import(TRANSFER_OUT, sender = "SBIUPI")
        val first = all().single()
        clock.setTo(Instant.parse("2026-03-05T07:00:00Z"))
        transactions.update(first.copy(note = "Paid Rahul back"), learnCategory = false)

        val second = importer.import(TRANSFER_IN, sender = "SBIINB")

        assertThat(second).isNotInstanceOf(SmsImportOutcome.PairedAsTransfer::class.java)
        assertThat(all().map { it.type }).containsExactly(TransactionType.EXPENSE, TransactionType.INCOME)
    }

    @Test
    fun `a message for a spend already typed in by hand is not added again`() = runTest {
        transactions.create(
            type = TransactionType.EXPENSE,
            amount = Money.of("250"),
            accountId = hdfc.id,
            categoryId = null,
            merchant = "Swiggy",
            occurredOn = day,
            learnCategory = false,
        )

        assertThat(importer.import(SPEND_250, sender = "SBIUPI")).isEqualTo(SmsImportOutcome.Duplicate)
        assertThat(all()).hasSize(1)
    }

    /** Removing an added row is the way to say "not this one"; it must stay removed. */
    @Test
    fun `a message the user removed is not added back when it is read again`() = runTest {
        importer.import(SPEND_250, sender = "SBIUPI")
        transactions.delete(all().single().id)

        assertThat(importer.import(SPEND_250, sender = "SBIUPI")).isEqualTo(SmsImportOutcome.Duplicate)
        assertThat(importer.import(SPEND_250, sender = "SBIUPI", receivedOn = day, fromInboxScan = true))
            .isEqualTo(SmsImportOutcome.Duplicate)
        assertThat(all()).isEmpty()
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /** Every live row, pending or not. The range read covers confirmed rows only. */
    private suspend fun all(): List<Transaction> =
        transactions.getInRange(DateRange(day.minusDays(10), day.plusDays(10))) +
            transactions.observePending().first()

    private suspend fun balanceOf(account: Account): Money =
        BalanceCalculator.balances(listOf(hdfc, icici), all())
            .single { it.account.id == account.id }
            .currentBalance

    private companion object {
        const val SPEND_250 =
            "Dear UPI user A/C X4821 debited by 250.0 on date 05Mar26 trf to SWIGGY Refno 412345678901. If not u? call 1800111109. -SBI"
        const val TRANSFER_OUT =
            "Dear UPI user A/C X4821 debited by 5000.0 on date 05Mar26 trf to RAHUL KUMAR Refno 412345678955. If not u? call 1800111109. -SBI"
        const val TRANSFER_IN =
            "Dear SBI User, your A/c X1190-credited by Rs.5000 on 05Mar26 transfer from RAHUL KUMAR Ref No 412345678955 -SBI"
    }
}

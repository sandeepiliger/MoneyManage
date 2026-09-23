package ai.labs32.khaata.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.Money
import com.google.common.truth.Truth.assertThat
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
 * Upgrading an install that already holds data must open, keep every row, and leave every
 * balance exactly where it was.
 *
 * `app/schemas/` has never been committed, so there is no v1 schema file for
 * `MigrationTestHelper`. This builds the real v1 shape instead: the current schema (which Room
 * creates from the entities) with the one column v2 added dropped again, and `user_version` set
 * back to 1. Opening it with the production migrations then runs MIGRATION_1_2 and Room's own
 * schema validation, which is what would crash the app on launch if the migration were wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration1To2Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-1-2-test.db"

    private val account = Account(
        id = "acc-hdfc",
        name = "HDFC",
        type = AccountType.BANK,
        openingBalance = Money.of("50000"),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private val transactions = listOf(
        transaction("t1", TransactionType.EXPENSE, "1200", LocalDate.of(2025, 12, 20)),
        transaction("t2", TransactionType.EXPENSE, "800", LocalDate.of(2026, 1, 5)),
        transaction("t3", TransactionType.INCOME, "30000", LocalDate.of(2026, 1, 31)),
    )

    @Before
    fun setUp() {
        context.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun `a version 1 database with data upgrades and every balance is unchanged`() = runTest {
        // Current schema plus data, written through Room itself.
        open().apply {
            accountDao().upsertAll(listOf(account.toEntity()))
            transactionDao().upsertAll(transactions.map { it.toEntity() })
            close()
        }

        // Turn it back into version 1: the only difference is the column v2 added.
        downgradeToVersion1()

        // Opening runs MIGRATION_1_2 and then Room's validation of the result.
        val upgraded = open()
        try {
            val restored = upgraded.accountDao().findById(account.id)!!.toDomain()
            assertThat(restored.name).isEqualTo("HDFC")
            assertThat(restored.openingBalance).isEqualTo(Money.of("50000"))
            // Null means "no snapshot date": the pre-upgrade behaviour, every transaction counts.
            assertThat(restored.openingBalanceDate).isNull()

            val total = upgraded.transactionDao().signedTotalForAccount(account.id, since = restored.openingBalanceDate)
            // 50,000 - 1,200 - 800 + 30,000
            assertThat(Money.ofMinor(total, restored.currency) + restored.openingBalance)
                .isEqualTo(Money.of("78000"))

            val batched = upgraded.transactionDao().observeAccountTotals().first()
                .single { it.accountId == account.id }
            assertThat(batched.totalMinor).isEqualTo(total)

            assertThat(upgraded.openHelper.readableDatabase.version).isEqualTo(KhaataDatabase.VERSION)
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun `after the upgrade an opening balance date is stored and honoured`() = runTest {
        // Room creates the file lazily, on first use; touch it so there is a file to downgrade.
        open().apply {
            openHelper.writableDatabase
            close()
        }
        downgradeToVersion1()

        val upgraded = open()
        try {
            val dated = account.copy(openingBalanceDate = LocalDate.of(2026, 1, 1))
            upgraded.accountDao().upsertAll(listOf(dated.toEntity()))
            upgraded.transactionDao().upsertAll(transactions.map { it.toEntity() })

            val restored = upgraded.accountDao().findById(account.id)!!.toDomain()
            assertThat(restored.openingBalanceDate).isEqualTo(LocalDate.of(2026, 1, 1))

            // The December expense is before the snapshot and no longer moves the balance.
            val total = upgraded.transactionDao().signedTotalForAccount(account.id, since = restored.openingBalanceDate)
            assertThat(Money.ofMinor(total, restored.currency) + restored.openingBalance)
                .isEqualTo(Money.of("79200"))
            val batched = upgraded.transactionDao().observeAccountTotals().first()
                .single { it.accountId == account.id }
            assertThat(batched.totalMinor).isEqualTo(total)
        } finally {
            upgraded.close()
        }
    }

    /** Removes the column v2 added and marks the file as version 1, keeping every row. */
    private fun downgradeToVersion1() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            try {
                db.execSQL("ALTER TABLE accounts DROP COLUMN openingBalanceDate")
            } catch (unsupported: android.database.SQLException) {
                // DROP COLUMN needs SQLite 3.35; rebuild the table the long way on anything older.
                rebuildAccountsWithoutNewColumn(db)
            }
            db.version = 1
        }
    }

    private fun rebuildAccountsWithoutNewColumn(db: SQLiteDatabase) {
        val create = db.rawQuery(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'accounts'",
            null,
        ).use { it.moveToFirst(); it.getString(0) }
        val indices = db.rawQuery(
            "SELECT sql FROM sqlite_master WHERE type = 'index' AND tbl_name = 'accounts' AND sql IS NOT NULL",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        val columns = db.rawQuery("PRAGMA table_info(accounts)", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }.filter { it != "openingBalanceDate" }.joinToString(", ") { "`$it`" }

        db.execSQL("PRAGMA foreign_keys = OFF")
        db.execSQL("ALTER TABLE accounts RENAME TO accounts_v2")
        db.execSQL(create.replace(Regex(""",\s*`openingBalanceDate` INTEGER"""), ""))
        db.execSQL("INSERT INTO accounts ($columns) SELECT $columns FROM accounts_v2")
        db.execSQL("DROP TABLE accounts_v2")
        indices.forEach(db::execSQL)
    }

    private fun open(): KhaataDatabase =
        Room.databaseBuilder(context, KhaataDatabase::class.java, name)
            .addMigrations(*KhaataDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    private fun transaction(id: String, type: TransactionType, amount: String, on: LocalDate) = Transaction(
        id = id,
        type = type,
        amount = Money.of(amount),
        accountId = account.id,
        categoryId = null,
        occurredOn = on,
        createdAt = Instant.parse("2026-02-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-02-01T00:00:00Z"),
    )
}

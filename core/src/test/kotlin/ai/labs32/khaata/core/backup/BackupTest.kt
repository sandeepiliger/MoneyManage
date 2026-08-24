package ai.labs32.khaata.core.backup

import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.testing.Fixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class BackupSerializerTest {

    private val backup = BackupFile(
        appVersion = "1.0.0",
        exportedAt = Instant.parse("2026-03-15T10:30:00Z"),
        accounts = listOf(
            Fixtures.account(id = "acc-1", openingBalance = "50000"),
            Fixtures.account(id = "acc-2", name = "ICICI", openingBalance = "12345.67"),
        ),
        categories = listOf(Fixtures.category("cat-food", "Food")),
        transactions = listOf(
            Fixtures.expense(id = "t1", amount = "850", on = LocalDate.of(2026, 3, 14)),
            Fixtures.income(id = "t2", amount = "112000", on = LocalDate.of(2026, 3, 1)),
            Fixtures.transfer(id = "t3", amount = "20000", on = LocalDate.of(2026, 3, 5)),
        ),
        budgets = listOf(Fixtures.budget()),
        goals = listOf(Fixtures.goal()),
        subscriptions = listOf(Fixtures.subscription()),
        recurringRules = listOf(Fixtures.recurring()),
        investments = listOf(Fixtures.investment()),
    )

    @Test
    fun `a backup round trips without losing anything`() {
        val text = BackupSerializer.write(backup)
        val result = BackupSerializer.read(text)

        assertThat(result).isInstanceOf(BackupReadResult.Success::class.java)
        val restored = (result as BackupReadResult.Success).backup

        assertThat(restored.accounts).isEqualTo(backup.accounts)
        assertThat(restored.transactions).isEqualTo(backup.transactions)
        assertThat(restored.budgets).isEqualTo(backup.budgets)
        assertThat(restored.goals).isEqualTo(backup.goals)
        assertThat(restored.subscriptions).isEqualTo(backup.subscriptions)
        assertThat(restored.recurringRules).isEqualTo(backup.recurringRules)
        assertThat(restored.investments).isEqualTo(backup.investments)
        assertThat(restored.exportedAt).isEqualTo(backup.exportedAt)
    }

    @Test
    fun `amounts survive the round trip to the paisa`() {
        val text = BackupSerializer.write(backup)
        val restored = (BackupSerializer.read(text) as BackupReadResult.Success).backup

        assertThat(restored.accounts[1].openingBalance).isEqualTo(Money.of("12345.67"))
        assertThat(restored.transactions.first { it.id == "t1" }.amount).isEqualTo(Money.of("850"))
    }

    @Test
    fun `amounts are written as text rather than JSON numbers`() {
        // A JSON number round-tripped through a double is exactly the precision loss this
        // codebase avoids everywhere else.
        val text = BackupSerializer.write(backup)
        assertThat(text).contains("\"INR:1234567\"")
    }

    @Test
    fun `dates are written in readable ISO form`() {
        val text = BackupSerializer.write(backup)
        assertThat(text).contains("2026-03-14")
    }

    @Test
    fun `an empty file is rejected with an explanation`() {
        val result = BackupSerializer.read("")
        assertThat(result).isInstanceOf(BackupReadResult.Invalid::class.java)
        assertThat((result as BackupReadResult.Invalid).message).isNotEmpty()
    }

    @Test
    fun `random text is rejected rather than throwing`() {
        assertThat(BackupSerializer.read("this is not json"))
            .isInstanceOf(BackupReadResult.Invalid::class.java)
        assertThat(BackupSerializer.read("{\"unclosed\": "))
            .isInstanceOf(BackupReadResult.Invalid::class.java)
        assertThat(BackupSerializer.read("[]"))
            .isInstanceOf(BackupReadResult.Invalid::class.java)
    }

    @Test
    fun `a malformed amount is rejected rather than silently zeroed`() {
        val text = BackupSerializer.write(backup).replace("\"INR:85000\"", "\"NOT-A-MONEY\"")
        val result = BackupSerializer.read(text)

        assertThat(result).isInstanceOf(BackupReadResult.Invalid::class.java)
    }

    @Test
    fun `a malformed date is rejected rather than defaulted`() {
        val text = BackupSerializer.write(backup).replace("2026-03-14", "not-a-date")
        assertThat(BackupSerializer.read(text)).isInstanceOf(BackupReadResult.Invalid::class.java)
    }

    @Test
    fun `a newer schema version is refused rather than partially imported`() {
        val text = BackupSerializer.write(backup.copy(schemaVersion = 99))
        val result = BackupSerializer.read(text)

        assertThat(result).isInstanceOf(BackupReadResult.TooNew::class.java)
        assertThat((result as BackupReadResult.TooNew).fileSchemaVersion).isEqualTo(99)
    }

    @Test
    fun `unknown fields from a newer build are ignored rather than failing`() {
        val text = BackupSerializer.write(backup).replaceFirst("{", "{\"futureField\": 42,")
        assertThat(BackupSerializer.read(text)).isInstanceOf(BackupReadResult.Success::class.java)
    }

    @Test
    fun `the summary reports what will be restored`() {
        val summary = backup.summary()

        assertThat(summary.accountCount).isEqualTo(2)
        assertThat(summary.transactionCount).isEqualTo(3)
        assertThat(summary.goalCount).isEqualTo(1)
        assertThat(summary.totalRecords).isEqualTo(11)
    }

    @Test
    fun `an empty backup is valid`() {
        val empty = BackupFile(appVersion = "1.0.0", exportedAt = Instant.EPOCH)
        val result = BackupSerializer.read(BackupSerializer.write(empty))

        assertThat(result).isInstanceOf(BackupReadResult.Success::class.java)
        assertThat((result as BackupReadResult.Success).backup.summary().totalRecords).isEqualTo(0)
    }
}

class CsvTest {

    private val accounts = listOf(
        Fixtures.account(id = "acc-hdfc", name = "HDFC Bank"),
        Fixtures.account(id = "acc-icici", name = "ICICI Bank"),
    )
    private val categories = listOf(Fixtures.category("cat-food", "Food"))

    @Test
    fun `export writes a header and one row per transaction`() {
        val transactions = listOf(
            Fixtures.expense(id = "t1", amount = "850", merchant = "Swiggy", on = LocalDate.of(2026, 3, 14)),
            Fixtures.income(id = "t2", amount = "112000", on = LocalDate.of(2026, 3, 1)),
        )
        val csv = CsvExporter.export(transactions, accounts, categories)
        val lines = csv.trim().lines()

        assertThat(lines).hasSize(3)
        assertThat(lines.first()).startsWith("Date,Type,Amount")
        assertThat(lines[1]).contains("2026-03-14")
        assertThat(lines[1]).contains("850.00")
        assertThat(lines[1]).contains("Swiggy")
        assertThat(lines[1]).contains("HDFC Bank")
    }

    @Test
    fun `deleted transactions are not exported`() {
        val transactions = listOf(
            Fixtures.expense(id = "t1", amount = "850"),
            Fixtures.expense(id = "t2", amount = "500", deletedAt = Instant.EPOCH),
        )
        assertThat(CsvExporter.export(transactions, accounts, categories).trim().lines()).hasSize(2)
    }

    @Test
    fun `fields containing commas and quotes are escaped`() {
        val transactions = listOf(
            Fixtures.expense(id = "t1", amount = "850", merchant = "Shop, \"The\" Best"),
        )
        val csv = CsvExporter.export(transactions, accounts, categories)

        assertThat(csv).contains("\"Shop, \"\"The\"\" Best\"")
        // The escaping must survive a round trip.
        val parsed = CsvImporter().parse(csv)
        assertThat(parsed.rows.single().merchant).isEqualTo("Shop, \"The\" Best")
    }

    @Test
    fun `a merchant name that looks like a formula is neutralised`() {
        // Without this guard, opening the export in a spreadsheet executes the cell.
        val transactions = listOf(
            Fixtures.expense(id = "t1", amount = "850", merchant = "=1+1"),
        )
        val csv = CsvExporter.export(transactions, accounts, categories)

        assertThat(csv).doesNotContain(",=1+1,")
        assertThat(csv).contains("'=1+1")
        // ...and the original text still comes back on import.
        assertThat(CsvImporter().parse(csv).rows.single().merchant).isEqualTo("=1+1")
    }

    @Test
    fun `export and import round trip`() {
        val transactions = listOf(
            Fixtures.expense(id = "t1", amount = "850", merchant = "Swiggy", on = LocalDate.of(2026, 3, 14)),
            Fixtures.income(id = "t2", amount = "112000", on = LocalDate.of(2026, 3, 1)),
        )
        val parsed = CsvImporter().parse(CsvExporter.export(transactions, accounts, categories))

        assertThat(parsed.rejected).isEmpty()
        assertThat(parsed.rows).hasSize(2)
        assertThat(parsed.rows[0].amount).isEqualTo(Money.of("850"))
        assertThat(parsed.rows[0].type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.rows[0].occurredOn).isEqualTo(LocalDate.of(2026, 3, 14))
        assertThat(parsed.rows[1].type).isEqualTo(TransactionType.INCOME)
    }

    @Test
    fun `a bank statement style export is understood`() {
        val statement = """
            Txn Date,Narration,Withdrawal,Amount,Ref No
            14/03/2026,UPI-SWIGGY-BLR,,-850.00,412345678901
            01/03/2026,SALARY CREDIT,,112000.00,NEFT99887766
        """.trimIndent()
        val parsed = CsvImporter().parse(statement)

        assertThat(parsed.rows).hasSize(2)
        assertThat(parsed.rows[0].occurredOn).isEqualTo(LocalDate.of(2026, 3, 14))
        // A leading minus means money out, which is the bank export convention.
        assertThat(parsed.rows[0].type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.rows[0].amount).isEqualTo(Money.of("850"))
        assertThat(parsed.rows[0].merchant).isEqualTo("UPI-SWIGGY-BLR")
        assertThat(parsed.rows[1].type).isEqualTo(TransactionType.INCOME)
    }

    @Test
    fun `one bad row is reported without failing the whole import`() {
        val csv = """
            Date,Type,Amount,Merchant
            2026-03-14,EXPENSE,850,Swiggy
            not-a-date,EXPENSE,500,Broken
            2026-03-16,EXPENSE,abc,Also Broken
            2026-03-17,EXPENSE,1200,Uber
        """.trimIndent()
        val parsed = CsvImporter().parse(csv)

        assertThat(parsed.rows).hasSize(2)
        assertThat(parsed.rejected).hasSize(2)
        assertThat(parsed.rejected[0].recordId).isEqualTo("line 3")
        assertThat(parsed.rejected[0].reason).contains("date")
        assertThat(parsed.rejected[1].recordId).isEqualTo("line 4")
        assertThat(parsed.rejected[1].reason).contains("amount")
    }

    @Test
    fun `a file without recognisable columns is rejected with a reason`() {
        val parsed = CsvImporter().parse("foo,bar,baz\n1,2,3")

        assertThat(parsed.rows).isEmpty()
        assertThat(parsed.rejected.single().reason).contains("date column")
    }

    @Test
    fun `an empty file is rejected`() {
        val parsed = CsvImporter().parse("")
        assertThat(parsed.hasRows).isFalse()
        assertThat(parsed.rejected).isNotEmpty()
    }

    @Test
    fun `several indian date formats are accepted`() {
        val csv = """
            Date,Amount,Merchant
            14/03/2026,-100,A
            14-03-2026,-100,B
            14-Mar-2026,-100,C
            2026-03-14,-100,D
        """.trimIndent()
        val parsed = CsvImporter().parse(csv)

        assertThat(parsed.rejected).isEmpty()
        assertThat(parsed.rows.map { it.occurredOn }.distinct())
            .containsExactly(LocalDate.of(2026, 3, 14))
    }

    @Test
    fun `tags are split on semicolons`() {
        val csv = "Date,Amount,Tags\n2026-03-14,-100,work;travel;reimbursable"
        assertThat(CsvImporter().parse(csv).rows.single().tags)
            .containsExactly("work", "travel", "reimbursable")
    }

    @Test
    fun `a currency column is honoured`() {
        val csv = "Date,Amount,Currency\n2026-03-14,-100,USD"
        assertThat(CsvImporter().parse(csv).rows.single().amount.currency)
            .isEqualTo(CurrencyCode.USD)
    }
}

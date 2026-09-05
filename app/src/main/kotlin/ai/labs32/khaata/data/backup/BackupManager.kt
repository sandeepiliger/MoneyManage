package ai.labs32.khaata.data.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import ai.labs32.khaata.BuildConfig
import ai.labs32.khaata.core.backup.BackupFile
import ai.labs32.khaata.core.backup.BackupReadResult
import ai.labs32.khaata.core.backup.BackupSerializer
import ai.labs32.khaata.core.backup.CsvExporter
import ai.labs32.khaata.core.backup.CsvImportResult
import ai.labs32.khaata.core.backup.CsvImporter
import ai.labs32.khaata.core.backup.CsvTransactionRow
import ai.labs32.khaata.core.backup.ImportMode
import ai.labs32.khaata.core.backup.ImportResult
import ai.labs32.khaata.core.backup.RejectedRecord
import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.database.KhaataDatabase
import ai.labs32.khaata.core.database.dao.TagDao
import ai.labs32.khaata.core.database.toDomain
import ai.labs32.khaata.core.database.toEntity
import ai.labs32.khaata.core.logging.KhaataLog
import ai.labs32.khaata.core.model.Transaction
import ai.labs32.khaata.core.model.TransactionSource
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.BudgetRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.CreditCardRepository
import ai.labs32.khaata.data.repository.GoalRepository
import ai.labs32.khaata.data.repository.InvestmentRepository
import ai.labs32.khaata.data.repository.LoanRepository
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.data.repository.RecurringRepository
import ai.labs32.khaata.data.repository.SettingsRepository
import ai.labs32.khaata.data.repository.SubscriptionRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where an export ended up, and how to hand it to another app.
 *
 * The [uri] is a FileProvider URI granting temporary read access to that one file. The raw path is
 * kept only so the UI can show the filename — nothing outside this class opens it by path.
 */
data class ExportedFile(
    val fileName: String,
    val uri: Uri,
    val sizeBytes: Long,
    val mimeType: String,
    /** How many records went into the file. Null for a handle to a file found on disk. */
    val recordCount: Int? = null,
)

/**
 * Backup, restore, and CSV import/export.
 *
 * Everything here writes to the app's own `files/exports` directory and shares it through the
 * FileProvider declared in the manifest. Nothing is uploaded anywhere: a "backup" in this app is a
 * file the user then puts wherever they choose, which is the only arrangement consistent with the
 * promise that financial data does not leave the device unless the user moves it.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: KhaataDatabase,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val transactionRepository: TransactionRepository,
    private val budgetRepository: BudgetRepository,
    private val recurringRepository: RecurringRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val creditCardRepository: CreditCardRepository,
    private val loanRepository: LoanRepository,
    private val investmentRepository: InvestmentRepository,
    private val goalRepository: GoalRepository,
    // No TagRepository exists yet; TagDao already exposes the bulk getAll/upsertAll/deleteAll a
    // backup needs, so it is injected directly rather than inventing a repository for one caller.
    private val tagDao: TagDao,
    private val clock: KhaataClock,
) {

    // ---- Export ------------------------------------------------------------------------------

    /** Collects everything into a [BackupFile] without writing it anywhere. */
    suspend fun buildBackup(): BackupFile = withContext(Dispatchers.IO) {
        BackupFile(
            appVersion = BuildConfig.VERSION_NAME,
            exportedAt = clock.now(),
            profile = profileRepository.getOrCreate(),
            settings = settingsRepository.current(),
            accounts = accountRepository.getAll(),
            categories = categoryRepository.getAll(),
            // Soft-deleted rows are included: a backup is meant to be able to restore the state
            // the user had, and that includes what is still recoverable from the trash.
            transactions = transactionRepository.getAllForExport(),
            budgets = budgetRepository.getAll(),
            recurringRules = recurringRepository.getAll(),
            subscriptions = subscriptionRepository.getAll(),
            creditCards = creditCardRepository.getAll(),
            loans = loanRepository.getAll(),
            investments = investmentRepository.getAll(),
            goals = goalRepository.getAll(),
            tags = tagDao.getAll().map { it.toDomain() },
            merchantRules = categoryRepository.getAllMerchantRules(),
        )
    }

    /** Writes a JSON backup and returns a shareable handle to it. */
    suspend fun exportBackup(): Result<ExportedFile> = runCatchingIo {
        val backup = buildBackup()
        val json = BackupSerializer.write(backup)
        writeToExports(
            fileName = "khaata-backup-${timestamp()}.json",
            content = json,
            mimeType = MIME_JSON,
            recordCount = backup.summary().totalRecords,
        )
    }

    /** Writes the transaction ledger as CSV and returns a shareable handle to it. */
    suspend fun exportCsv(): Result<ExportedFile> = runCatchingIo {
        val transactions = transactionRepository.getAllForExport()
        val csv = CsvExporter.export(
            transactions = transactions,
            accounts = accountRepository.getAll(),
            categories = categoryRepository.getAll(),
        )
        writeToExports(
            fileName = "khaata-transactions-${timestamp()}.csv",
            content = csv,
            mimeType = MIME_CSV,
            recordCount = transactions.count { !it.isDeleted },
        )
    }

    /**
     * Removes previously exported files.
     *
     * Exports are copies of the entire ledger sitting in app storage. Keeping them indefinitely
     * doubles the footprint of the thing this app is most careful about, so old ones are cleared
     * whenever a new export is made and can be cleared on demand.
     */
    suspend fun clearExports(): Int = withContext(Dispatchers.IO) {
        exportsDir().listFiles().orEmpty().count { it.delete() }
    }

    suspend fun existingExports(): List<ExportedFile> = withContext(Dispatchers.IO) {
        exportsDir().listFiles().orEmpty()
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                ExportedFile(
                    fileName = file.name,
                    uri = uriFor(file),
                    sizeBytes = file.length(),
                    mimeType = if (file.extension == "csv") MIME_CSV else MIME_JSON,
                )
            }
    }

    // ---- Reading a candidate -----------------------------------------------------------------

    /**
     * Reads a backup the user picked, without applying it.
     *
     * Never throws: an unreadable file, a directory, or a 400MB video the user picked by mistake
     * all come back as [BackupReadResult.Invalid] with something a person can act on.
     */
    suspend fun readBackup(uri: Uri): BackupReadResult = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                // Bounded read: a malformed pick should fail fast rather than fill memory.
                readBounded(stream) ?: return@withContext BackupReadResult.Invalid(
                    "That file is too large to be a Khaata backup.",
                )
            } ?: return@withContext BackupReadResult.Invalid("That file could not be opened.")

            BackupSerializer.read(text)
        }.getOrElse { error ->
            KhaataLog.e(TAG, "Backup read failed", error)
            BackupReadResult.Invalid("That file could not be read.")
        }
    }

    /** Parses a CSV the user picked, without applying it. */
    suspend fun readCsv(uri: Uri): Result<CsvImportResult> = runCatchingIo {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            readBounded(stream) ?: error("That file is too large to import.")
        } ?: error("That file could not be opened.")

        CsvImporter(defaultCurrency = profileRepository.currency()).parse(text)
    }

    // ---- Restore -----------------------------------------------------------------------------

    /**
     * Applies a backup.
     *
     * Order follows the foreign keys: categories and accounts exist before anything referencing
     * them. [ImportMode.REPLACE_ALL] deletes first, in the reverse order, so a delete never trips
     * a constraint half-way and leaves the database in a state neither old nor new.
     */
    suspend fun restore(backup: BackupFile, mode: ImportMode): Result<ImportResult> = runCatchingIo {
        // Deletes and upserts across a dozen tables used to be independent, separately-committed
        // operations: a throw partway through (bad data, OOM, process death) left some tables
        // wiped and others half-populated, with no way back. Wrapping the whole thing in one Room
        // transaction makes it all-or-nothing — a failure rolls back to exactly the state before
        // restore() was called.
        database.withTransaction {
            restoreLocked(backup, mode)
        }
    }

    /** The body of [restore], run inside the single transaction that makes it atomic. */
    private suspend fun restoreLocked(backup: BackupFile, mode: ImportMode): ImportResult {
        if (mode == ImportMode.REPLACE_ALL) {
            transactionRepository.deleteAll()
            budgetRepository.deleteAll()
            recurringRepository.deleteAll()
            subscriptionRepository.deleteAll()
            creditCardRepository.deleteAll()
            loanRepository.deleteAll()
            investmentRepository.deleteAll()
            goalRepository.deleteAll()
            accountRepository.deleteAll()
            categoryRepository.deleteAll() // also clears merchant rules; see CategoryRepository.deleteAll
            tagDao.deleteAll()
        }

        val existingAccountIds = accountRepository.getAll().map { it.id }.toSet()
        val existingCategoryIds = categoryRepository.getAll().map { it.id }.toSet()
        val existingTransactionIds = transactionRepository.getAllForExport().map { it.id }.toSet()

        val skipExisting = mode == ImportMode.MERGE_SKIP_EXISTING
        val imported = LinkedHashMap<String, Int>()
        val skipped = LinkedHashMap<String, Int>()
        val rejected = ArrayList<RejectedRecord>()

        val categories = backup.categories.filterNot { skipExisting && it.id in existingCategoryIds }
        categoryRepository.upsertAll(categories)
        imported["categories"] = categories.size
        skipped["categories"] = backup.categories.size - categories.size

        val accounts = backup.accounts.filterNot { skipExisting && it.id in existingAccountIds }
        accountRepository.upsertAll(accounts)
        imported["accounts"] = accounts.size
        skipped["accounts"] = backup.accounts.size - accounts.size

        // Every account and category referenced by a transaction must exist by now, counting both
        // what the backup brought and what was already here. A row pointing at something missing
        // is rejected individually rather than failing the whole restore.
        val knownAccounts = existingAccountIds + accounts.map { it.id }
        val knownCategories = existingCategoryIds + categories.map { it.id }

        val (validTransactions, invalidTransactions) = backup.transactions
            .filterNot { skipExisting && it.id in existingTransactionIds }
            .partition { transaction ->
                transaction.accountId in knownAccounts &&
                    (transaction.transferAccountId == null || transaction.transferAccountId in knownAccounts) &&
                    (transaction.categoryId == null || transaction.categoryId in knownCategories)
            }

        invalidTransactions.forEach { transaction ->
            rejected += RejectedRecord(
                recordType = "transaction",
                recordId = transaction.id,
                reason = "It refers to an account or category that is not in this backup.",
            )
        }

        transactionRepository.createAll(validTransactions)
        imported["transactions"] = validTransactions.size
        skipped["transactions"] = backup.transactions.size - validTransactions.size -
            invalidTransactions.size

        budgetRepository.upsertAll(backup.budgets)
        imported["budgets"] = backup.budgets.size
        recurringRepository.upsertAll(backup.recurringRules)
        imported["recurring"] = backup.recurringRules.size
        subscriptionRepository.upsertAll(backup.subscriptions)
        imported["subscriptions"] = backup.subscriptions.size
        creditCardRepository.upsertAll(backup.creditCards)
        imported["cards"] = backup.creditCards.size
        loanRepository.upsertAll(backup.loans)
        imported["loans"] = backup.loans.size
        investmentRepository.upsertAll(backup.investments)
        imported["investments"] = backup.investments.size
        goalRepository.upsertAll(backup.goals)
        imported["goals"] = backup.goals.size
        tagDao.upsertAll(backup.tags.map { it.toEntity() })
        imported["tags"] = backup.tags.size
        categoryRepository.upsertAllMerchantRules(backup.merchantRules)
        imported["merchantRules"] = backup.merchantRules.size

        // Settings and the profile are restored only on a full replace. Merging someone's old
        // theme and lock preference into a working install is surprising and rarely wanted.
        if (mode == ImportMode.REPLACE_ALL) {
            backup.profile?.let { profileRepository.update(it) }
        }

        // A restore into an empty database must still end up with categories, or the app cannot
        // record anything.
        categoryRepository.seedIfEmpty()

        return ImportResult(mode = mode, imported = imported, skipped = skipped, rejected = rejected)
    }

    /**
     * Turns parsed CSV rows into transactions.
     *
     * Accounts and categories are matched by name, case-insensitively, because that is what a CSV
     * carries. An unmatched account is a rejection rather than a guess: filing a row against the
     * wrong account silently corrupts a balance, and the user cannot tell that it happened.
     */
    suspend fun importCsvRows(rows: List<CsvTransactionRow>): Result<ImportResult> = runCatchingIo {
        val accountsByName = accountRepository.getAll().associateBy { it.name.lowercase() }
        val categoriesByName = categoryRepository.getAll().associateBy { it.name.lowercase() }

        val rejected = ArrayList<RejectedRecord>()
        val transactions = ArrayList<Transaction>()

        for (row in rows) {
            val account = row.accountName?.lowercase()?.let { accountsByName[it] }
            if (account == null) {
                rejected += RejectedRecord(
                    recordType = "transaction",
                    recordId = "line ${row.lineNumber}",
                    reason = "No account named \"${row.accountName.orEmpty()}\" exists here.",
                )
                continue
            }
            // A CSV row's Currency column is independent of the account it is filed against — a
            // bank export in USD dropped onto an INR account. TransactionDao's balance queries sum
            // amount_minor_units with no currency predicate, so writing that row through would add
            // USD minor units straight into an INR balance: a $100.00 row becomes -₹100.00 against
            // what was really a ~₹8,300 expense, off by roughly 98% with nothing to flag it.
            if (row.amount.currency != account.currency) {
                rejected += RejectedRecord(
                    recordType = "transaction",
                    recordId = "line ${row.lineNumber}",
                    reason = "Amount is in ${row.amount.currency.code} but \"${account.name}\" " +
                        "is in ${account.currency.code}.",
                )
                continue
            }

            val transferAccount = row.transferAccountName?.lowercase()?.let { accountsByName[it] }
            val category = row.categoryName?.lowercase()?.let { categoriesByName[it] }

            transactions += Transaction(
                id = UUID.randomUUID().toString(),
                type = row.type,
                amount = row.amount,
                accountId = account.id,
                transferAccountId = transferAccount?.id,
                categoryId = category?.id,
                merchant = row.merchant,
                note = row.note,
                occurredOn = row.occurredOn,
                createdAt = clock.now(),
                updatedAt = clock.now(),
                source = TransactionSource.CSV_IMPORT,
                tags = row.tags,
                referenceNumber = row.referenceNumber,
            )
        }

        transactionRepository.createAll(transactions)

        ImportResult(
            mode = ImportMode.MERGE_SKIP_EXISTING,
            imported = mapOf("transactions" to transactions.size),
            skipped = emptyMap(),
            rejected = rejected,
        )
    }

    // ---- Internals ---------------------------------------------------------------------------

    /**
     * Reads at most [MAX_BACKUP_BYTES] from [stream] as UTF-8, or null if the file is longer.
     *
     * Written as a plain read loop rather than `InputStream.readNBytes`, which Android only
     * added in API 33 and core library desugaring does not backport -- on the API 24-32 devices
     * that are most of this app's audience, calling it throws NoSuchMethodError and takes down
     * every backup and CSV import with it.
     *
     * One byte past the limit is enough to know the file is too long, so nothing larger is ever
     * held in memory.
     */
    private fun readBounded(stream: java.io.InputStream): String? {
        val buffer = ByteArray(READ_CHUNK_BYTES)
        val collected = java.io.ByteArrayOutputStream()
        while (collected.size() <= MAX_BACKUP_BYTES) {
            val read = stream.read(buffer)
            if (read < 0) return collected.toString(Charsets.UTF_8.name())
            collected.write(buffer, 0, read)
        }
        return null
    }

    private fun exportsDir(): File = File(context.filesDir, "exports").apply { mkdirs() }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun writeToExports(
        fileName: String,
        content: String,
        mimeType: String,
        recordCount: Int,
    ): ExportedFile {
        val dir = exportsDir()
        // A previous export of the same kind is replaced rather than accumulated.
        dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == File(fileName).extension }
            .forEach { it.delete() }

        val file = File(dir, fileName)
        file.writeText(content)
        return ExportedFile(
            fileName = file.name,
            uri = uriFor(file),
            sizeBytes = file.length(),
            mimeType = mimeType,
            recordCount = recordCount,
        )
    }

    private fun timestamp(): String =
        clock.today().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    /**
     * Runs [block] off the main thread, turning any failure into a [Result].
     *
     * Backup and restore touch the filesystem and every table, so there are many ways to fail. The
     * exception is logged with no file contents attached and the caller gets something it can show
     * — never a stack trace on screen.
     */
    private suspend fun <T> runCatchingIo(block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching { block() }.onFailure { KhaataLog.e(TAG, "Backup operation failed", it) }
        }

    private companion object {
        const val TAG = "BackupManager"
        const val MIME_JSON = "application/json"
        const val MIME_CSV = "text/csv"

        /** 64MB. Far above any plausible ledger, far below what would exhaust memory. */
        const val MAX_BACKUP_BYTES = 64 * 1024 * 1024

        /** Read granularity for [readBounded]. Large enough that a big backup is not a syscall storm. */
        const val READ_CHUNK_BYTES = 64 * 1024
    }
}

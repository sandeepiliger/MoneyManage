package ai.labs32.khaata.core.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reads and writes backup files.
 *
 * Import is treated as parsing hostile input, because in practice it is: files get truncated by
 * cloud sync, hand-edited, produced by an older build, or renamed from something else entirely.
 * Every failure mode here returns a typed [BackupReadResult] the UI can explain, rather than an
 * exception that becomes a crash report.
 */
object BackupSerializer {

    private val json = Json {
        prettyPrint = true
        // A backup written by a newer build must still be readable by an older one for the fields
        // it does understand, rather than failing outright on an unknown key.
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Serialises [backup] to JSON text. */
    fun write(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    /**
     * Parses backup JSON.
     *
     * Never throws: every outcome is a [BackupReadResult] carrying a message the UI can show.
     */
    fun read(text: String): BackupReadResult {
        if (text.isBlank()) {
            return BackupReadResult.Invalid("The file is empty.")
        }
        if (text.length > MAX_BACKUP_BYTES) {
            return BackupReadResult.Invalid(
                "The file is larger than ${MAX_BACKUP_BYTES / 1_000_000}MB and was not read.",
            )
        }

        val backup = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (error: SerializationException) {
            return BackupReadResult.Invalid(
                "This does not look like a Khaata backup file. ${error.message.orEmpty()}".trim(),
            )
        } catch (error: IllegalArgumentException) {
            // Thrown by our own field serialisers on a malformed amount or date.
            return BackupReadResult.Invalid("The file contains invalid data: ${error.message}")
        }

        if (backup.schemaVersion > BackupFile.CURRENT_SCHEMA_VERSION) {
            return BackupReadResult.TooNew(backup.schemaVersion)
        }
        if (backup.schemaVersion < 1) {
            return BackupReadResult.Invalid("Unrecognised backup version ${backup.schemaVersion}.")
        }
        return BackupReadResult.Success(backup)
    }

    /** 64MB — far above any realistic personal ledger, low enough to refuse a runaway file. */
    private const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
}

/** The outcome of reading a backup file. */
sealed interface BackupReadResult {
    data class Success(val backup: BackupFile) : BackupReadResult

    /** The file is not a readable backup. [message] is safe to show the user verbatim. */
    data class Invalid(val message: String) : BackupReadResult

    /**
     * Written by a newer version of the app.
     *
     * Refused rather than partially imported: guessing at a schema we do not know risks writing
     * wrong amounts, and a user prompted to update loses nothing.
     */
    data class TooNew(val fileSchemaVersion: Int) : BackupReadResult
}

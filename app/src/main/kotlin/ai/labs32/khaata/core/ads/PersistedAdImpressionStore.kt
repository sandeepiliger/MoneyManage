package ai.labs32.khaata.core.ads

import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.database.dao.AppStateDao
import ai.labs32.khaata.core.database.dao.TransactionDao
import ai.labs32.khaata.core.database.entity.AppStateEntity
import ai.labs32.khaata.core.logging.KhaataLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Frequency-capping state, kept in the app's own key-value table.
 *
 * What is stored is deliberately minimal: for each placement, the timestamp of the last impression
 * and a per-day counter; plus the date the app was first used. That is the smallest thing that can
 * answer "may I interrupt this person again yet?" and it is not a profile — no ad identifiers, no
 * click history, nothing about what was shown.
 *
 * It lives in the database rather than in memory because the interstitial rules are per-day and
 * per-week; a counter that resets whenever the process is killed would let a user be interrupted
 * far more often than the cap says.
 */
@Singleton
class PersistedAdImpressionStore @Inject constructor(
    private val appStateDao: AppStateDao,
    private val transactionDao: TransactionDao,
    private val clock: KhaataClock,
) : AdImpressionStore {

    override suspend fun record(placement: AdPlacement, at: Instant) {
        appStateDao.put(
            AppStateEntity(
                key = lastKey(placement),
                value = at.toEpochMilli().toString(),
                updatedAt = clock.now(),
            ),
        )

        val day = at.toLocalDate()
        val current = readCount(placement, day)
        appStateDao.put(
            AppStateEntity(
                key = countKey(placement, day),
                value = (current + 1).toString(),
                updatedAt = clock.now(),
            ),
        )

        // Yesterday's counter is dropped as today's is written, so the table cannot accumulate a
        // row per placement per day forever.
        appStateDao.remove(countKey(placement, day.minusDays(1)))
    }

    override suspend fun lastImpression(placement: AdPlacement): Instant? =
        appStateDao.get(lastKey(placement))?.toLongOrNull()?.let(Instant::ofEpochMilli)

    override suspend fun countToday(placement: AdPlacement, now: Instant): Int =
        readCount(placement, now.toLocalDate())

    /**
     * How long the app has been in use.
     *
     * The first-use date is written the first time this is asked, which is the first time an ad
     * placement is evaluated. That is deliberately not process start: a user who never reaches a
     * placement never gets a row written for them.
     */
    override suspend fun daysSinceFirstUse(now: Instant): Int {
        val stored = appStateDao.get(KEY_FIRST_USE)?.toLongOrNull()
        if (stored == null) {
            appStateDao.put(
                AppStateEntity(
                    key = KEY_FIRST_USE,
                    value = now.toEpochMilli().toString(),
                    updatedAt = clock.now(),
                ),
            )
            return 0
        }
        return ChronoUnit.DAYS.between(Instant.ofEpochMilli(stored), now).toInt().coerceAtLeast(0)
    }

    override suspend fun transactionCount(): Int = transactionDao.count()

    private suspend fun readCount(placement: AdPlacement, day: LocalDate): Int =
        appStateDao.get(countKey(placement, day))?.toIntOrNull() ?: 0

    private fun lastKey(placement: AdPlacement) = "ad_last_${placement.name}"

    private fun countKey(placement: AdPlacement, day: LocalDate) =
        "ad_count_${placement.name}_$day"

    /**
     * The device's own day boundary.
     *
     * "Three a day" has to mean the user's day. Using UTC would give a user in IST a cap that
     * resets at 5:30am, which is neither what the config says nor what anyone would expect.
     */
    private fun Instant.toLocalDate(): LocalDate = try {
        atZone(ZoneId.systemDefault()).toLocalDate()
    } catch (error: Exception) {
        KhaataLog.w(TAG, "Falling back to UTC for the ad day boundary")
        atZone(ZoneId.of("UTC")).toLocalDate()
    }

    private companion object {
        const val TAG = "AdImpressionStore"
        const val KEY_FIRST_USE = "ad_first_use_at"
    }
}

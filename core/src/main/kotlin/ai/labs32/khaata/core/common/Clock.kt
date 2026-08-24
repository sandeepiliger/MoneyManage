package ai.labs32.khaata.core.common

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The app's source of "now".
 *
 * Nothing in the domain layer calls `LocalDate.now()` directly. Budget periods, statement cycles,
 * recurring schedules and insight comparisons are all date-sensitive, and a test that cannot pin
 * the date can only ever be flaky. Injecting the clock also makes month-boundary behaviour
 * something we can assert on rather than hope about.
 */
interface KhaataClock {
    fun now(): Instant
    fun zone(): ZoneId

    fun today(): LocalDate = LocalDateTime.ofInstant(now(), zone()).toLocalDate()
    fun nowLocal(): LocalDateTime = LocalDateTime.ofInstant(now(), zone())
}

/** The real clock. Defaults to the device zone; India is UTC+5:30 and has no DST. */
class SystemKhaataClock(private val zoneId: ZoneId = ZoneId.systemDefault()) : KhaataClock {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = zoneId
}

/** A clock that can be moved deliberately. Test-only in practice, but harmless in production. */
class FixedKhaataClock(
    @Volatile private var instant: Instant,
    private val zoneId: ZoneId = ZoneId.of("Asia/Kolkata"),
) : KhaataClock {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId

    fun setTo(newInstant: Instant) {
        instant = newInstant
    }

    fun setTo(date: LocalDate) {
        instant = date.atStartOfDay(zoneId).toInstant()
    }

    companion object {
        fun at(date: LocalDate, zoneId: ZoneId = ZoneId.of("Asia/Kolkata")): FixedKhaataClock =
            FixedKhaataClock(date.atStartOfDay(zoneId).toInstant(), zoneId)
    }
}

package ai.labs32.khaata.data.repository

import ai.labs32.khaata.core.common.KhaataClock
import ai.labs32.khaata.core.database.dao.UserProfileDao
import ai.labs32.khaata.core.database.toDomainOrNull
import ai.labs32.khaata.core.database.toEntity
import ai.labs32.khaata.core.model.UserProfile
import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single local profile.
 *
 * There is no sign-in and no server identity; a profile is a name and a handful of preferences on
 * this device. The row is created lazily on first read so the rest of the app can assume it
 * exists.
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val userProfileDao: UserProfileDao,
    private val clock: KhaataClock,
) {

    fun observe(): Flow<UserProfile?> =
        userProfileDao.observeById(UserProfile.SINGLETON_ID).map { it?.toDomainOrNull() }

    /** The profile, creating a default one if this is a fresh install. */
    suspend fun getOrCreate(): UserProfile {
        userProfileDao.findById(UserProfile.SINGLETON_ID)?.toDomainOrNull()?.let { return it }
        val now = clock.now()
        val profile = UserProfile(createdAt = now, updatedAt = now)
        userProfileDao.upsert(profile.toEntity())
        return profile
    }

    suspend fun update(profile: UserProfile) {
        userProfileDao.upsert(profile.copy(updatedAt = clock.now()).toEntity())
    }

    suspend fun setDisplayName(name: String?) {
        update(getOrCreate().copy(displayName = name?.trim()?.takeIf { it.isNotBlank() }))
    }

    suspend fun setCurrency(currency: CurrencyCode) {
        update(getOrCreate().copy(currency = currency))
    }

    suspend fun setLanguage(languageTag: String) {
        update(getOrCreate().copy(languageTag = languageTag))
    }

    suspend fun setMonthlyIncome(income: Money?) {
        update(getOrCreate().copy(monthlyIncome = income))
    }

    /**
     * Sets the day the user's financial month starts.
     *
     * Capped at 28 by [UserProfile] so every month actually has the day; a start day of 31 would
     * silently shift in February.
     */
    suspend fun setMonthStartDay(day: Int) {
        update(getOrCreate().copy(monthStartDay = day.coerceIn(1, 28)))
    }

    suspend fun markOnboardingComplete() {
        getOrCreate()
        userProfileDao.markOnboardingComplete(UserProfile.SINGLETON_ID, clock.now())
    }

    suspend fun setDemoMode(enabled: Boolean) {
        getOrCreate()
        userProfileDao.setDemoMode(UserProfile.SINGLETON_ID, enabled, clock.now())
    }

    suspend fun currency(): CurrencyCode = getOrCreate().currency

    suspend fun deleteAll() = userProfileDao.deleteAll()
}

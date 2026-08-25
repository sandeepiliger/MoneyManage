package ai.labs32.khaata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.labs32.khaata.core.model.ThemePreference
import ai.labs32.khaata.core.security.AppLockManager
import ai.labs32.khaata.core.security.LockState
import ai.labs32.khaata.data.repository.ProfileRepository
import ai.labs32.khaata.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The state the app shell needs before it can show anything.
 *
 * Deliberately small: theme, onboarding status and lock state are the only three things that
 * decide what the very first frame looks like. Everything else is a screen's own concern.
 */
data class MainUiState(
    val isLoading: Boolean = true,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val hasCompletedOnboarding: Boolean = false,
    val lockState: LockState = LockState.Unlocked,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository,
    private val appLockManager: AppLockManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // The profile row is created here if this is a fresh install, so every other screen
            // can assume it exists.
            profileRepository.getOrCreate()

            combine(
                settingsRepository.settings,
                profileRepository.observe(),
                appLockManager.lockState,
            ) { settings, profile, lockState ->
                MainUiState(
                    isLoading = false,
                    theme = settings.theme,
                    hasCompletedOnboarding = profile?.hasCompletedOnboarding == true,
                    lockState = lockState,
                )
            }.collect { _uiState.value = it }
        }
    }

    fun onOnboardingComplete() {
        viewModelScope.launch { profileRepository.markOnboardingComplete() }
    }

    fun onUnlocked() = appLockManager.unlock()
}

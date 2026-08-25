package ai.labs32.khaata

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ai.labs32.khaata.core.ads.AdMobAdProvider
import ai.labs32.khaata.core.ads.AdProvider
import ai.labs32.khaata.core.notifications.KhaataNotifier
import ai.labs32.khaata.core.security.LockState
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.feature.lock.LockScreen
import ai.labs32.khaata.feature.onboarding.OnboardingScreen
import ai.labs32.khaata.navigation.KhaataNavHost
import ai.labs32.khaata.navigation.Routes
import ai.labs32.khaata.navigation.TopLevelDestination
import ai.labs32.khaata.ui.KhaataBottomBar
import ai.labs32.khaata.ui.KhaataFloatingAddButton
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The app's only activity.
 *
 * A [FragmentActivity] rather than a ComponentActivity because `BiometricPrompt` needs a fragment
 * host; everything above it is Compose.
 *
 * The splash screen is held until the first real state has loaded, so the app never flashes an
 * empty dashboard on the way to onboarding or to the lock screen.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var adProvider: AdProvider
    @Inject lateinit var notifier: KhaataNotifier

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Holding the splash avoids a visible flash of the wrong screen while onboarding state
        // and the lock mode are read.
        splashScreen.setKeepOnScreenCondition { viewModel.uiState.value.isLoading }

        enableEdgeToEdge()

        val openQuickAdd = intent?.action == KhaataNotifier.ACTION_QUICK_ADD ||
            intent?.action == ACTION_QUICK_ADD_ALIAS

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            KhaataTheme(themePreference = state.theme) {
                Surface(Modifier.fillMaxSize()) {
                    when {
                        state.isLoading -> Unit // The splash screen is still showing.

                        !state.hasCompletedOnboarding -> OnboardingScreen(
                            onFinished = viewModel::onOnboardingComplete,
                        )

                        else -> KhaataApp(
                            openQuickAdd = openQuickAdd,
                            lockState = state.lockState,
                            onUnlocked = viewModel::onUnlocked,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The ad provider needs a resumed activity to show a full-screen ad. Set here rather
        // than held long-term so a destroyed activity is never retained.
        (adProvider as? AdMobAdProvider)?.currentActivity = this
    }

    override fun onPause() {
        (adProvider as? AdMobAdProvider)?.currentActivity = null
        super.onPause()
    }

    companion object {
        const val ACTION_QUICK_ADD_ALIAS = "ai.labs32.khaata.action.QUICK_ADD"
    }
}

/**
 * The app shell: bottom navigation, the add button, and the lock overlay.
 *
 * The lock is drawn over the app rather than as a separate destination, so unlocking returns the
 * user exactly where they were instead of resetting them to the dashboard.
 */
@Composable
private fun KhaataApp(
    openQuickAdd: Boolean,
    lockState: LockState,
    onUnlocked: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val topLevel = remember(currentRoute) { TopLevelDestination.fromRoute(currentRoute) }
    val showChrome = topLevel != null

    // A notification's "Record it" action opens straight into entry rather than making the user
    // find the button.
    LaunchedEffect(openQuickAdd) {
        if (openQuickAdd) navController.navigate(Routes.ADD_TRANSACTION)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                AnimatedVisibility(
                    visible = showChrome,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    KhaataBottomBar(
                        currentDestination = topLevel,
                        onSelect = { destination ->
                            navController.navigate(destination.route) {
                                // Tapping a tab returns to its root rather than stacking copies,
                                // and preserves each tab's own scroll position.
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = showChrome,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    KhaataFloatingAddButton(
                        onClick = { navController.navigate(Routes.ADD_TRANSACTION) },
                    )
                }
            },
        ) { padding ->
            KhaataNavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(padding),
            )
        }

        // Drawn last so it covers everything, including the bottom bar and any open sheet.
        if (lockState is LockState.Locked) {
            LockScreen(mode = lockState.mode, onUnlocked = onUnlocked)
        }
    }
}

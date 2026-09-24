package ai.labs32.khaata

import android.content.Intent
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ai.labs32.khaata.core.ads.AdMobAdProvider
import ai.labs32.khaata.core.ads.AdProvider
import ai.labs32.khaata.core.notifications.KhaataNotifier
import ai.labs32.khaata.core.security.LockState
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import ai.labs32.khaata.feature.lock.LockScreen
import ai.labs32.khaata.feature.onboarding.OnboardingScreen
import ai.labs32.khaata.navigation.KhaataNavHost
import ai.labs32.khaata.navigation.Routes
import ai.labs32.khaata.navigation.TopLevelDestination
import ai.labs32.khaata.ui.KhaataBottomBar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The app's only activity.
 *
 * An [AppCompatActivity], which is a `FragmentActivity`: `BiometricPrompt` needs a fragment host,
 * and AndroidX applies the in-app language choice to AppCompat activities on Android versions that
 * have no per-app language of their own (see [ai.labs32.khaata.core.locale.AppLocales]). Everything
 * above it is Compose.
 *
 * The splash screen is held until the first real state has loaded, so the app never flashes an
 * empty dashboard on the way to onboarding or to the lock screen.
 *
 * `launchMode="singleTask"` means a notification or shortcut tapped while the app is already
 * running is delivered to the *existing* instance via [onNewIntent], not a fresh [onCreate]. A
 * deep link read only in `onCreate` therefore works exactly once, from a cold start, and silently
 * does nothing the rest of the time — which is why [pendingDeepLink] is Compose state updated from
 * both places, rather than a value computed once from `intent`.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var adProvider: AdProvider
    @Inject lateinit var notifier: KhaataNotifier
    @Inject lateinit var categoryRepository: CategoryRepository

    private var pendingDeepLink by mutableStateOf<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Holding the splash avoids a visible flash of the wrong screen while onboarding state
        // and the lock mode are read.
        splashScreen.setKeepOnScreenCondition { viewModel.uiState.value.isLoading }

        enableEdgeToEdge()

        pendingDeepLink = DeepLink.from(intent)

        // A change of language recreates this activity; the lists screens already hold were read
        // in the old one. Built-in category names are translated as they are read, so have them
        // read again.
        lifecycleScope.launch { categoryRepository.refreshNamesIfLanguageChanged() }

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
                            deepLink = pendingDeepLink,
                            onDeepLinkConsumed = { pendingDeepLink = null },
                            lockState = state.lockState,
                            onUnlocked = viewModel::onUnlocked,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink = DeepLink.from(intent)
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

/** Where a tapped notification or shortcut should take the user once the app is on screen. */
private sealed interface DeepLink {
    data object QuickAdd : DeepLink
    data object ReviewImports : DeepLink

    /** A transaction added from a bank message, opened from its notification to check or remove. */
    data class OpenTransaction(val transactionId: String) : DeepLink

    companion object {
        fun from(intent: Intent?): DeepLink? = when (intent?.action) {
            KhaataNotifier.ACTION_QUICK_ADD, MainActivity.ACTION_QUICK_ADD_ALIAS -> QuickAdd
            KhaataNotifier.ACTION_REVIEW_IMPORTS -> ReviewImports
            KhaataNotifier.ACTION_OPEN_TRANSACTION ->
                intent.getStringExtra(KhaataNotifier.EXTRA_TRANSACTION_ID)?.let { OpenTransaction(it) }
            else -> null
        }
    }
}

/**
 * The app shell: bottom navigation with the add button in it, and the lock overlay.
 *
 * The lock is drawn over the app rather than as a separate destination, so unlocking returns the
 * user exactly where they were instead of resetting them to the dashboard.
 */
@Composable
private fun KhaataApp(
    deepLink: DeepLink?,
    onDeepLinkConsumed: () -> Unit,
    lockState: LockState,
    onUnlocked: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val topLevel = remember(currentRoute) { TopLevelDestination.fromRoute(currentRoute) }
    val showChrome = topLevel != null

    // A notification's "Record it" action opens straight into entry, and "new transaction to
    // confirm" opens straight into the review list, rather than making the user find either.
    // Consuming the link back to null (rather than keying off the enum alone) is what makes a
    // second tap of the same notification navigate again while the app is already open --
    // MainActivity.onNewIntent sets the same value, but LaunchedEffect only reruns on a change.
    LaunchedEffect(deepLink) {
        when (deepLink) {
            DeepLink.QuickAdd -> navController.navigate(Routes.ADD_TRANSACTION)
            DeepLink.ReviewImports -> navController.navigate(Routes.PENDING_IMPORTS)
            is DeepLink.OpenTransaction -> navController.navigate(Routes.transactionDetail(deepLink.transactionId))
            null -> return@LaunchedEffect
        }
        onDeepLinkConsumed()
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
                        onAdd = { navController.navigate(Routes.ADD_TRANSACTION) },
                        // Straight into listening -- holding add means "let me say it", so the
                        // screen opens the recogniser itself rather than landing on a form with
                        // a microphone still to press.
                        onAddByVoice = {
                            navController.navigate(Routes.naturalLanguageEntry(listen = true))
                        },
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

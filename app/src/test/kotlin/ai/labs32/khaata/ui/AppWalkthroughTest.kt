package ai.labs32.khaata.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.hasScrollToIndexAction
import ai.labs32.khaata.MainActivity
import ai.labs32.khaata.R
import ai.labs32.khaata.core.locale.AppLanguage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the real app -- real Application, Hilt graph, Room database and navigation -- through
 * every main screen, asserting that each one actually shows its content, and saves a screenshot
 * of each for review.
 *
 * Unit tests of view models cannot catch a screen that composes but draws nothing: the redesign's
 * first build shipped a bottom bar that stretched over the whole window and left every tab blank,
 * and every other test passed. These assert on what is on screen and where, which is what broke.
 *
 * Screenshots land in `app/build/screenshots/<test>/`, and CI publishes them so the layouts can
 * be looked at without a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = PHONE)
class AppWalkthroughTest {

    @get:Rule(order = 0)
    val testName = TestName()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private var shot = 0

    // ---- The walkthrough ---------------------------------------------------------------------

    @Test
    fun everyMainScreenShowsItsContentWithSampleData() {
        startWithSampleData()

        // Home: the headline, the "needs you" list, and the bar at the bottom of the window.
        onText(R.string.home_needs_you).assertIsDisplayed()
        assertBottomBarAtTheBottom()
        snap("home")

        // "Record it" on a bill in Needs you records the oldest missed date, so the count of
        // dates still waiting behind it goes down.
        // "Was due 3 Apr · 5 more waiting" becomes "Was due 3 May · 4 more waiting".
        val wasDue = str(R.string.recurring_was_due, "").trim()
        val waitingBefore = firstTextContaining(wasDue)
        assertThat(waitingBefore).isNotNull()
        compose.onAllNodes(hasText(str(R.string.recurring_confirm_post)) and hasClickAction())[0].performClick()
        compose.waitUntil(TIMEOUT_MS) { firstTextContaining(wasDue) != waitingBefore }
        snap("home-after-record")

        scrollMainList()
        snap("home-scrolled")

        // Activity: the in/out strip, then the analysis half.
        tab(R.string.nav_activity).performClick()
        waitForText(R.string.activity_in)
        onText(R.string.activity_in).assertIsDisplayed()
        assertBottomBarAtTheBottom()
        snap("activity")
        compose.onNode(hasText(str(R.string.activity_view_analysis)) and hasClickAction()).performClick()
        compose.waitForIdle()
        snap("activity-analysis")
        compose.onNode(hasText(str(R.string.activity_view_list)) and hasClickAction()).performClick()
        compose.waitForIdle()

        // Plan: budgets, bills and goals; then last month.
        tab(R.string.nav_plan).performClick()
        waitForText(R.string.plan_bills)
        assertBottomBarAtTheBottom()
        snap("plan")
        scrollMainList()
        snap("plan-scrolled")
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(0)
        compose.onNode(hasContentDescription(str(R.string.plan_previous_month))).performClick()
        val lastMonth = java.time.YearMonth.now().minusMonths(1)
            .format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"))
        waitForText(lastMonth)
        waitForText(str(R.string.plan_final_figures, lastMonth.substringBefore(' ')))
        snap("plan-last-month")

        // Money: net worth split into what you have and what you owe.
        tab(R.string.nav_money).performClick()
        waitForText(R.string.money_you_have)
        onText(R.string.money_you_have).assertIsDisplayed()
        assertBottomBarAtTheBottom()
        snap("money")
        scrollMainList()
        snap("money-scrolled")

        // Settings, from Home's avatar. Home comes back scrolled where it was left, so wait for
        // the avatar at the top rather than for a card that may be scrolled out of the list.
        tab(R.string.nav_home).performClick()
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(0)
        val avatar = hasContentDescription(str(R.string.settings_title)) and hasClickAction()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(avatar).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(avatar).performClick()
        waitForText(R.string.settings_title)
        snap("settings")
        pressBack()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(avatar).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun anExpenseAddedFromTheBarAppearsInActivity() {
        startWithSampleData()

        compose.onNode(hasContentDescription(str(R.string.nav_add_transaction), substring = true)).performClick()
        waitForText(R.string.transaction_add_title)
        // The keypad appears once the editor has loaded the accounts.
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasContentDescription("4") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        snap("add-empty")

        listOf("4", "5", "0").forEach { digit ->
            compose.onNode(hasContentDescription(digit) and hasClickAction()).performClick()
        }
        // The merchant is what picks the category; Swiggy is one of the seeded merchant rules.
        compose.onNode(hasSetTextAction()).performTextInput("Swiggy")
        compose.waitForIdle()

        val saveLabel = str(R.string.transaction_save_amount, "₹450")
        waitForText(saveLabel)
        snap("add-filled")
        compose.onNode(hasText(saveLabel) and hasClickAction()).performClick()

        // Saving returns to where add was opened from, and the spend is in the list.
        waitForText(R.string.home_needs_you)
        tab(R.string.nav_activity).performClick()
        waitForText(R.string.activity_in)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText("Swiggy", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        snap("activity-after-add")
    }

    @Test
    fun onboardingTakesThreeStepsToAnEmptyHome() {
        waitForText(R.string.onboarding_try_demo)
        snap("welcome")
        continueButton().performClick()

        // Step 1: the account.
        waitForText(str(R.string.onboarding_step_of, 1, 3))
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("HDFC Savings")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("52340")
        snap("step-1-account")
        continueButton().performClick()

        // Step 2: bank SMS, which can be skipped.
        waitForText(str(R.string.onboarding_step_of, 2, 3))
        snap("step-2-sms")
        compose.onNode(hasText(str(R.string.action_skip)) and hasClickAction()).performClick()

        // Step 3: done.
        waitForText(str(R.string.onboarding_step_of, 3, 3))
        snap("step-3-done")
        compose.onNode(hasText(str(R.string.action_done)) and hasClickAction()).performClick()

        // An empty ledger: the empty state, with the bar where it belongs.
        waitForText(R.string.dashboard_empty_title)
        assertBottomBarAtTheBottom()
        snap("home-empty")
    }

    /** Dark theme: the same screens, to check every surface and figure stays legible. */
    @Test
    @Config(qualifiers = "+night")
    fun theMainScreensInDarkTheme() {
        startWithSampleData()
        snap("home")
        tab(R.string.nav_activity).performClick()
        waitForText(R.string.activity_in)
        snap("activity")
        tab(R.string.nav_plan).performClick()
        waitForText(R.string.plan_bills)
        snap("plan")
        tab(R.string.nav_money).performClick()
        waitForText(R.string.money_you_have)
        snap("money")
    }

    /**
     * Hindi: labels are longer and set in the system font (the brand face has no Devanagari), so
     * this is where truncation and overflow would show first.
     */
    @Test
    @Config(qualifiers = "hi-rIN-w393dp-h851dp-xhdpi")
    fun theMainScreensInHindi() = walkTheMainScreensInThisLanguage()

    /** Kannada: tall stacked consonants below the line, where tight line heights clip first. */
    @Test
    @Config(qualifiers = "kn-rIN-w393dp-h851dp-xhdpi")
    fun theMainScreensInKannada() = walkTheMainScreensInThisLanguage()

    /** Telugu: marks above and below every line, like Kannada. */
    @Test
    @Config(qualifiers = "te-rIN-w393dp-h851dp-xhdpi")
    fun theMainScreensInTelugu() = walkTheMainScreensInThisLanguage()

    /** Tamil: the longest words of any language here, so chips and buttons are tested hardest. */
    @Test
    @Config(qualifiers = "ta-rIN-w393dp-h851dp-xhdpi")
    fun theMainScreensInTamil() = walkTheMainScreensInThisLanguage()

    /**
     * Every main tab, the add screen and Settings with its language picker, in whatever language
     * the test's qualifiers set. Each wait is on a translated string, so a screen that fell back
     * to English, or failed to show, fails here rather than only looking wrong in a screenshot.
     */
    private fun walkTheMainScreensInThisLanguage() {
        startWithSampleData()
        assertBottomBarAtTheBottom()
        snap("home")
        tab(R.string.nav_activity).performClick()
        waitForText(R.string.activity_in)
        snap("activity")
        tab(R.string.nav_plan).performClick()
        waitForText(R.string.plan_bills)
        snap("plan")
        tab(R.string.nav_money).performClick()
        waitForText(R.string.money_you_have)
        snap("money")

        tab(R.string.nav_home).performClick()
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(0)
        val avatar = hasContentDescription(str(R.string.settings_title)) and hasClickAction()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(avatar).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(avatar).performClick()
        waitForText(R.string.settings_title)
        compose.onNode(hasText(str(R.string.settings_appearance))).performScrollTo()
        compose.onNode(hasText(str(R.string.settings_language_system)) and hasClickAction()).assertIsDisplayed()
        // Every language is on screen at once, the last included: nobody has to swipe to find theirs.
        compose.onNode(hasText(AppLanguage.TAMIL.nativeName) and hasClickAction()).assertIsDisplayed()
        snap("settings-language")
        pressBack()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(avatar).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(hasContentDescription(str(R.string.nav_add_transaction), substring = true)).performClick()
        waitForText(R.string.transaction_add_title)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasContentDescription("4") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        snap("add")
    }

    /** Tapping the tab you are already on takes the list back to its top, as in every big app. */
    @Test
    fun tappingTheOpenTabAgainScrollsBackToTheTop() {
        startWithSampleData()
        val avatar = hasContentDescription(str(R.string.settings_title)) and hasClickAction()
        fun avatarOnScreen() = compose.onAllNodes(avatar).fetchSemanticsNodes()
            .any { it.boundsInRoot.top >= 0f && it.boundsInRoot.bottom > 0f }
        assertThat(avatarOnScreen()).isTrue()

        repeat(3) { scrollMainList() }
        compose.waitUntil(TIMEOUT_MS) { !avatarOnScreen() }

        tab(R.string.nav_home).performClick()
        compose.waitUntil(TIMEOUT_MS) { avatarOnScreen() }
        // Still on Home: the tap scrolled, it did not navigate anywhere.
        onText(R.string.home_needs_you).assertExists()
    }

    /** A small phone (360dp x 640dp): the add screen's keypad and fields must still fit. */
    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun theAddScreenFitsASmallPhone() {
        startWithSampleData()
        snap("home")
        compose.onNode(hasContentDescription(str(R.string.nav_add_transaction), substring = true)).performClick()
        waitForText(R.string.transaction_add_title)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasContentDescription("4") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        // The whole keypad and the save button are on screen, not pushed below it.
        compose.onNode(hasContentDescription("0") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText(str(R.string.action_save)) and hasClickAction()).assertIsDisplayed()
        snap("add")
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private fun startWithSampleData() {
        waitForText(R.string.onboarding_try_demo)
        compose.onNode(hasText(str(R.string.onboarding_try_demo)) and hasClickAction()).performClick()
        waitForText(R.string.home_needs_you)
    }

    /** The text of the first node whose text contains [fragment], or null. */
    private fun firstTextContaining(fragment: String): String? =
        compose.onAllNodes(hasText(fragment, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }
            ?.joinToString()

    private fun str(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun onText(id: Int): SemanticsNodeInteraction =
        compose.onAllNodesWithText(str(id))[0]

    private fun waitForText(id: Int) = waitForText(str(id))

    private fun waitForText(text: String) {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** A bottom-bar tab: its label, on a node that can be clicked (headers with the same word cannot). */
    private fun tab(labelId: Int): SemanticsNodeInteraction =
        compose.onNode(hasText(str(labelId)) and hasClickAction() and isNavigationTab())

    private fun isNavigationTab() = SemanticsMatcher("is a selectable tab") { node ->
        node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Selected)
    }

    private fun continueButton(): SemanticsNodeInteraction =
        compose.onNode(hasText(str(R.string.action_continue)) and hasClickAction())

    /**
     * The bar sits in the bottom sixth of the window and is no taller than a bar should be. This
     * is the assertion the blank-screen build would have failed: its bar started at the top.
     */
    private fun assertBottomBarAtTheBottom() {
        val window = compose.onRoot().getBoundsInRoot()
        val home = tab(R.string.nav_home).getBoundsInRoot()
        assertThat(home.top.value).isGreaterThan(window.bottom.value * 5 / 6)
        assertThat((home.bottom - home.top).value).isLessThan(120f)
    }

    private fun scrollMainList() {
        val lists = compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes()
        if (lists.isEmpty()) return
        compose.onAllNodes(hasScrollToIndexAction())[0].performTouchInput { swipeUp() }
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Saves the window as a PNG under build/screenshots/<test>/. */
    private fun snap(name: String) {
        compose.waitForIdle()
        val bitmap = runCatching {
            compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        }.getOrElse {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                .also { bitmap -> view.draw(Canvas(bitmap)) }
        }
        val dir = File("build/screenshots/${javaClass.simpleName}-${testName.methodName}").apply { mkdirs() }
        shot++
        File(dir, "%02d-%s.png".format(shot, name)).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}

/** A mid-size phone: 393dp wide, like most of the Android phones this app is used on. */
private const val PHONE = "w393dp-h851dp-xhdpi"

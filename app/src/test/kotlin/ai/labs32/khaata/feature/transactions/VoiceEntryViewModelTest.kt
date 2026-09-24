package ai.labs32.khaata.feature.transactions

import ai.labs32.khaata.core.analytics.AnalyticsProvider
import ai.labs32.khaata.core.common.FixedKhaataClock
import ai.labs32.khaata.core.model.Account
import ai.labs32.khaata.core.model.AccountType
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.model.CategoryGroup
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.nlp.NaturalLanguageParser
import ai.labs32.khaata.data.repository.AccountRepository
import ai.labs32.khaata.data.repository.CategoryRepository
import ai.labs32.khaata.data.repository.TransactionRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Dictation into the natural-language entry screen.
 *
 * The recogniser hands back several readings of one utterance and its first is not always the
 * right one. These pin that the reading that parses goes into the input, the others stay one tap
 * away, and swapping one in replaces only what was dictated -- never what the user had typed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceEntryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val transactions = mockk<TransactionRepository>(relaxed = true)
    private val accounts = mockk<AccountRepository>(relaxed = true)
    private val categories = mockk<CategoryRepository>(relaxed = true)
    private val analytics = mockk<AnalyticsProvider>(relaxed = true)
    private val clock = FixedKhaataClock(Instant.parse("2026-03-15T06:30:00Z"))

    private val hdfc = Account(
        id = "acc-hdfc",
        name = "HDFC Savings",
        type = AccountType.BANK,
        openingBalance = Money.of("0"),
        createdAt = Instant.EPOCH,
    )
    private val food = Category(id = "cat-food", name = "Food & Dining", group = CategoryGroup.FOOD)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { accounts.observeActive() } returns flowOf(listOf(hdfc))
        every { categories.observeActive() } returns flowOf(listOf(food))
        coEvery { categories.suggestFor(any()) } returns null
        coEvery { transactions.frequentMerchants(any()) } returns listOf("Swiggy", "Blinkit")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = NaturalLanguageEntryViewModel(
        parser = NaturalLanguageParser(),
        transactionRepository = transactions,
        accountRepository = accounts,
        categoryRepository = categories,
        analytics = analytics,
        clock = clock,
    )

    @Test
    fun `the reading that makes sense is used, not the recogniser's first guess`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        model.onVoiceResult(listOf("spent for five on swiggy", "spent four fifty on swiggy", "spent 4 50 on swiggy"))
        advanceUntilIdle()

        val state = model.uiState.value
        assertThat(state.input).isEqualTo("spent four fifty on swiggy")
        assertThat(state.drafts.single().amount).isEqualTo(Money.of("450"))
        assertThat(state.voiceAlternatives)
            .containsExactly("spent for five on swiggy", "spent 4 50 on swiggy").inOrder()
    }

    @Test
    fun `a dictation is added after what is already there`() = runTest(dispatcher) {
        val model = viewModel()
        model.onInputChange("chai 20")
        model.onVoiceResult(listOf("petrol 1200"))
        advanceUntilIdle()

        assertThat(model.uiState.value.input).isEqualTo("chai 20 petrol 1200")
        assertThat(model.uiState.value.drafts.map { it.amount })
            .containsExactly(Money.of("20"), Money.of("1200")).inOrder()
    }

    @Test
    fun `picking another reading replaces only the dictated part`() = runTest(dispatcher) {
        val model = viewModel()
        model.onInputChange("chai 20")
        model.onVoiceResult(listOf("uber 320", "uber 330"))

        model.useVoiceAlternative("uber 330")
        advanceUntilIdle()

        val state = model.uiState.value
        assertThat(state.input).isEqualTo("chai 20 uber 330")
        // The reading it replaced is offered back, in case the first was right after all.
        assertThat(state.voiceAlternatives).containsExactly("uber 320")
        assertThat(state.drafts.map { it.amount }).containsExactly(Money.of("20"), Money.of("330")).inOrder()
    }

    @Test
    fun `typing puts the text in the user's hands and the suggestions go`() = runTest(dispatcher) {
        val model = viewModel()
        model.onVoiceResult(listOf("uber 320", "uber 330"))
        assertThat(model.uiState.value.voiceAlternatives).isNotEmpty()

        model.onInputChange("uber 320 airport")

        assertThat(model.uiState.value.voiceAlternatives).isEmpty()
    }

    @Test
    fun `the recogniser is told this user's merchants, categories and accounts`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()

        assertThat(model.voiceHints).containsAtLeast("Swiggy", "Blinkit", "Food & Dining", "HDFC Savings", "lakh")
        // Swiggy is both a merchant and a built-in hint; it is sent once.
        assertThat(model.voiceHints.count { it.equals("swiggy", ignoreCase = true) }).isEqualTo(1)
    }

    @Test
    fun `an empty result changes nothing`() = runTest(dispatcher) {
        val model = viewModel()
        model.onInputChange("chai 20")
        model.onVoiceResult(listOf("", "  "))

        assertThat(model.uiState.value.input).isEqualTo("chai 20")
    }
}

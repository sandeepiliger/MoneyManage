package ai.labs32.khaata.core.model

import ai.labs32.khaata.core.common.BigDecimalSerializer
import ai.labs32.khaata.core.common.LocalDateSerializer
import ai.labs32.khaata.core.money.Money
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDate

// ---------------------------------------------------------------------------------------------
// Subscriptions
// ---------------------------------------------------------------------------------------------

/**
 * A recurring service the user pays for.
 *
 * Modelled separately from [RecurringRule] even though the schedule maths is shared, because the
 * question a user asks about a subscription is different: not "what is due on the 5th?" but
 * "what am I paying for, and is it worth it?". That needs a per-service view with an annual cost
 * — the number that makes a ₹149/month service feel like the ₹1,788/year it actually is.
 */
@Serializable
data class Subscription(
    val id: String,
    val name: String,
    val amount: Money,
    val cycle: Frequency = Frequency.MONTHLY,
    @Serializable(with = LocalDateSerializer::class) val nextPaymentDate: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val startedOn: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val cancelledOn: LocalDate? = null,
    val categoryId: String? = null,
    val accountId: String? = null,
    /** Normalised merchant key ("netflix") used to match imported transactions to this service. */
    val merchantKey: String? = null,
    val reminderDaysBefore: Int = 2,
    val iconKey: String = "subscription",
    val colorSeed: Int = 0,
    val notes: String? = null,
    val isActive: Boolean = true,
) {
    init {
        require(amount.isPositive) { "A subscription amount must be positive, got $amount" }
        require(reminderDaysBefore in 0..30) {
            "Reminder lead time must be 0-30 days, got $reminderDaysBefore"
        }
    }

}

// ---------------------------------------------------------------------------------------------
// Credit cards
// ---------------------------------------------------------------------------------------------

/**
 * Credit card terms, attached to a [AccountType.CREDIT_CARD] account.
 *
 * The outstanding amount is not stored here: it is the linked account's balance, derived from
 * transactions like everything else. Storing it twice guarantees the two eventually disagree.
 */
@Serializable
data class CreditCard(
    val id: String,
    /** The account whose balance represents the outstanding amount. */
    val accountId: String,
    val cardName: String,
    val issuer: String,
    val creditLimit: Money,
    /** Day of month the statement is generated, 1-31. Clamped for short months. */
    val statementDayOfMonth: Int,
    /** Day of month payment is due, 1-31. May fall in the month after the statement. */
    val dueDayOfMonth: Int,
    /** Percentage of the outstanding amount charged as minimum due — typically 5% in India. */
    @Serializable(with = BigDecimalSerializer::class)
    val minimumDuePercent: BigDecimal = DEFAULT_MINIMUM_DUE_PERCENT,
    /** Floor on the minimum due, as most issuers apply one. */
    val minimumDueFloor: Money = Money.of(200, creditLimit.currency),
    val lastFourDigits: String? = null,
    val colorSeed: Int = 0,
    val isActive: Boolean = true,
) {
    init {
        require(creditLimit.isPositive) { "Credit limit must be positive, got $creditLimit" }
        require(statementDayOfMonth in 1..31) {
            "Statement day must be 1-31, got $statementDayOfMonth"
        }
        require(dueDayOfMonth in 1..31) { "Due day must be 1-31, got $dueDayOfMonth" }
        require(minimumDuePercent.signum() > 0 && minimumDuePercent <= BigDecimal("100")) {
            "Minimum due percent must be in (0, 100], got $minimumDuePercent"
        }
        require(lastFourDigits == null || lastFourDigits.matches(Regex("\\d{4}"))) {
            "lastFourDigits must be exactly four digits when set"
        }
    }

    companion object {
        val DEFAULT_MINIMUM_DUE_PERCENT: BigDecimal = BigDecimal("5")
    }
}

/** How healthy a card's utilisation is. Thresholds follow common credit-scoring guidance. */
enum class UtilisationBand {
    /** Under 30% — the range generally treated as healthy. */
    HEALTHY,

    /** 30–70%. */
    ELEVATED,

    /** Over 70%. */
    HIGH,

    /** At or over the limit. */
    OVER_LIMIT,
}

// ---------------------------------------------------------------------------------------------
// Loans
// ---------------------------------------------------------------------------------------------

/**
 * A loan with a fixed EMI — home, car, personal, education.
 *
 * We track and explain the loan; we do not advise on it. See
 * [ai.labs32.khaata.core.calc.LoanCalculator] for the amortisation maths.
 */
@Serializable
data class Loan(
    val id: String,
    val name: String,
    val lender: String? = null,
    val principal: Money,
    /** Nominal annual rate as a percentage, e.g. 8.5 for 8.5% p.a. */
    @Serializable(with = BigDecimalSerializer::class)
    val annualInterestRatePercent: BigDecimal,
    val tenureMonths: Int,
    @Serializable(with = LocalDateSerializer::class) val startDate: LocalDate,
    /** Overrides the computed EMI when the lender's figure differs slightly from ours. */
    val emiOverride: Money? = null,
    /** Day of month the EMI is debited. */
    val emiDayOfMonth: Int = startDate.dayOfMonth,
    /** Optional linked liability account, so the loan shows up in net worth. */
    val accountId: String? = null,
    val categoryId: String? = null,
    val colorSeed: Int = 0,
    val isClosed: Boolean = false,
) {
    init {
        require(principal.isPositive) { "Loan principal must be positive, got $principal" }
        require(annualInterestRatePercent.signum() >= 0) {
            "Interest rate cannot be negative, got $annualInterestRatePercent"
        }
        require(annualInterestRatePercent < BigDecimal("100")) {
            "Interest rate above 100% p.a. is almost certainly a data entry error, got $annualInterestRatePercent"
        }
        require(tenureMonths in 1..600) { "Loan tenure must be 1-600 months, got $tenureMonths" }
        require(emiDayOfMonth in 1..31) { "EMI day must be 1-31, got $emiDayOfMonth" }
        require(emiOverride == null || emiOverride.isPositive) { "EMI override must be positive" }
    }
}

// ---------------------------------------------------------------------------------------------
// Investments
// ---------------------------------------------------------------------------------------------

/**
 * A holding the user tracks by hand.
 *
 * This is a tracker, not a broking integration: there is no live price feed and no order path.
 * [currentValue] is whatever the user last entered, stamped with [valuedOn] so the UI can say how
 * stale it is rather than implying a live number.
 */
@Serializable
data class Investment(
    val id: String,
    val name: String,
    val kind: InvestmentKind,
    val investedAmount: Money,
    val currentValue: Money,
    @Serializable(with = LocalDateSerializer::class) val startedOn: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val valuedOn: LocalDate,
    val accountId: String? = null,
    /** Units held, for funds and stocks. Informational only. */
    @Serializable(with = BigDecimalSerializer::class)
    val units: BigDecimal? = null,
    val folioOrSymbol: String? = null,
    val notes: String? = null,
    val colorSeed: Int = 0,
    val isClosed: Boolean = false,
) {
    init {
        require(!investedAmount.isNegative) { "Invested amount cannot be negative" }
        require(!currentValue.isNegative) { "Current value cannot be negative" }
        require(!valuedOn.isBefore(startedOn)) { "Valuation date precedes the start date" }
    }
}

@Serializable
enum class InvestmentKind(val defaultIconKey: String) {
    MUTUAL_FUND("mutual_fund"),
    SIP("sip"),
    STOCK("stock"),
    FIXED_DEPOSIT("fd"),
    RECURRING_DEPOSIT("rd"),
    GOLD("gold"),
    PPF("ppf"),
    NPS("nps"),
    EPF("epf"),
    OTHER("investment"),
}

// ---------------------------------------------------------------------------------------------
// Goals
// ---------------------------------------------------------------------------------------------

/** Something the user is saving towards. */
@Serializable
data class Goal(
    val id: String,
    val name: String,
    val targetAmount: Money,
    val currentAmount: Money,
    @Serializable(with = LocalDateSerializer::class) val targetDate: LocalDate? = null,
    @Serializable(with = LocalDateSerializer::class) val startedOn: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val achievedOn: LocalDate? = null,
    /** Optional account whose balance funds this goal. */
    val accountId: String? = null,
    val iconKey: String = "goal",
    val colorSeed: Int = 0,
    val notes: String? = null,
    val isArchived: Boolean = false,
) {
    init {
        require(targetAmount.isPositive) { "A goal target must be positive, got $targetAmount" }
        require(!currentAmount.isNegative) { "Goal progress cannot be negative" }
        require(targetDate == null || !targetDate.isBefore(startedOn)) {
            "Goal target date $targetDate precedes its start $startedOn"
        }
    }

    val isAchieved: Boolean get() = currentAmount >= targetAmount
}

// ---------------------------------------------------------------------------------------------
// Supporting records
// ---------------------------------------------------------------------------------------------

/** A user-defined label that can be attached to transactions. */
@Serializable
data class Tag(
    val id: String,
    val name: String,
    val colorSeed: Int = 0,
    val usageCount: Int = 0,
)

/** A stored receipt image. Only a local file reference is kept — images never leave the device. */
@Serializable
data class Receipt(
    val id: String,
    val transactionId: String,
    /** Path relative to the app's private files directory. Never an absolute or external path. */
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    @Serializable(with = LocalDateSerializer::class) val capturedOn: LocalDate,
)

/**
 * A learned or user-defined merchant → category rule.
 *
 * These are what make the second Swiggy order a one-tap entry. They are built on-device from the
 * user's own corrections and never leave it.
 */
@Serializable
data class MerchantRule(
    val id: String,
    /** Normalised merchant token, lower-cased and stripped of punctuation. */
    val merchantKey: String,
    val categoryId: String,
    /** Preferred account, when the user consistently pays this merchant from one account. */
    val accountId: String? = null,
    /** How many times this pairing has been confirmed. Drives ranking between competing rules. */
    val confidence: Int = 1,
    /** True when the user set this explicitly; user rules always beat learned ones. */
    val isUserDefined: Boolean = false,
    /** True for the shipped India starter set, so it can be refreshed on upgrade. */
    val isSeeded: Boolean = false,
)

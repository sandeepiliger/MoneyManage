package ai.labs32.khaata.feature.shared

import ai.labs32.khaata.core.money.CurrencyCode
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyFormatter
import java.math.BigDecimal

/**
 * Turns a chart's raw float back into a formatted amount.
 *
 * The chart components take `Float` because that is what a Canvas needs, but every value in them
 * started life as a [Money]. This converts it back for the axis labels, the legend and the spoken
 * description, so those never show `84000.0` where the rest of the app shows `₹84,000`.
 *
 * The float round-trip loses sub-rupee precision. That is acceptable for a label on a chart and
 * nowhere else: no total, balance or budget figure is ever derived from one of these.
 */
fun chartMoneyFormatter(
    currency: CurrencyCode,
    compact: Boolean = true,
): (Float) -> String = { value ->
    val money = Money.of(BigDecimal(value.toDouble()), currency)
    if (compact) MoneyFormatter.compact(money) else MoneyFormatter.plain(money)
}

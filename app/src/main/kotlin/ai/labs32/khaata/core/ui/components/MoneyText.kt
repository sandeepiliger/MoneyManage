package ai.labs32.khaata.core.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.labs32.khaata.R
import ai.labs32.khaata.core.model.TransactionType
import ai.labs32.khaata.core.money.Money
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.money.MoneyStyle
import ai.labs32.khaata.core.money.SignStyle
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme

/**
 * Renders a monetary amount.
 *
 * Every amount in the app goes through here so three things stay true everywhere:
 *
 *  1. **Formatting is consistent** — Indian digit grouping, one decision about when decimals
 *     appear, one place to change it.
 *  2. **Direction is never conveyed by colour alone.** A sign is always present when direction
 *     matters, and [showDirectionIcon] adds a redundant arrow where the context is dense. This is
 *     the accessibility requirement that a green/red-only treatment fails.
 *  3. **Screen readers get words, not symbols.** The visible text is replaced in the semantics
 *     tree with a spoken form, so TalkBack says "eight hundred and fifty rupees spent" rather
 *     than "minus rupee-sign eight five zero".
 */
@Composable
fun MoneyText(
    money: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = KhaataTextStyles.amountMedium,
    moneyStyle: MoneyStyle = MoneyStyle.SMART,
    signStyle: SignStyle = SignStyle.NEGATIVE_ONLY,
    color: Color = LocalContentColor.current,
    showDirectionIcon: Boolean = false,
    maxLines: Int = 1,
) {
    val text = MoneyFormatter.format(money, moneyStyle, signStyle)
    val spoken = MoneyFormatter.accessibleDescription(
        money = money,
        spentLabel = stringResource(R.string.a11y_spent),
        receivedLabel = stringResource(R.string.a11y_received),
    )

    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showDirectionIcon && !money.isZero) {
            Icon(
                imageVector = if (money.isNegative) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(2.dp))
        }
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * An amount coloured and signed by transaction type.
 *
 * Transfers are shown in a neutral colour with a distinct icon rather than as a gain or a loss,
 * because a transfer is neither — treating it as spending is the mistake that makes a month's
 * totals meaningless.
 */
@Composable
fun TransactionAmountText(
    amount: Money,
    type: TransactionType,
    modifier: Modifier = Modifier,
    style: TextStyle = KhaataTextStyles.amountMedium,
) {
    val money = KhaataTheme.money
    val signed = when (type) {
        TransactionType.EXPENSE -> -amount.abs()
        TransactionType.INCOME -> amount.abs()
        TransactionType.TRANSFER -> amount.abs()
    }
    val color = when (type) {
        TransactionType.EXPENSE -> money.expense
        TransactionType.INCOME -> money.income
        TransactionType.TRANSFER -> money.neutral
    }

    if (type == TransactionType.TRANSFER) {
        val spoken = stringResource(
            R.string.a11y_transferred_amount,
            amount.amount.toPlainString(),
        )
        Row(
            modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = MoneyFormatter.format(signed, MoneyStyle.SMART, SignStyle.NEVER),
                style = style,
                color = color,
                maxLines = 1,
            )
        }
    } else {
        MoneyText(
            money = signed,
            modifier = modifier,
            style = style,
            signStyle = SignStyle.ALWAYS,
            color = color,
        )
    }
}

/**
 * A large headline figure, for the dashboard's balance.
 *
 * [hidden] blanks the value without unmounting it, so the privacy toggle does not reflow the
 * layout as it is switched.
 */
@Composable
fun HeroAmount(
    money: Money,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (hidden) {
        val description = stringResource(R.string.a11y_amount_hidden)
        Text(
            text = HIDDEN_PLACEHOLDER,
            modifier = modifier.clearAndSetSemantics { contentDescription = description },
            style = KhaataTextStyles.amountHero,
            color = color,
            maxLines = 1,
        )
    } else {
        MoneyText(
            money = money,
            modifier = modifier,
            style = KhaataTextStyles.amountHero,
            color = color,
        )
    }
}

/** Enough characters to occupy roughly the same width as a typical hidden amount. */
private const val HIDDEN_PLACEHOLDER = "••••••"

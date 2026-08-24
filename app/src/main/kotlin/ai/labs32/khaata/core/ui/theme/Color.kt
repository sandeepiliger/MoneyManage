package ai.labs32.khaata.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Khaata's palette.
 *
 * The brand is built around a deep indigo with a warm brass accent. Indigo reads as calm and
 * considered rather than alarming, which matters for an app people open when they are anxious
 * about money; brass gives it warmth without the urgency of orange or the alarm of red. This is
 * an original identity, chosen to sit apart from the reference apps rather than beside them.
 *
 * Semantic money colours are deliberately *not* plain red and green:
 *
 *  - Red-green is the most common form of colour blindness, so around one in twelve men would see
 *    income and expense as the same colour. Teal and rose stay distinguishable across the common
 *    deficiencies.
 *  - Red for spending frames every purchase as a mistake. Rose reads as "money left" without the
 *    scolding.
 *
 * Colour is never the only signal regardless: amounts carry a sign, statuses carry an icon and a
 * label, and charts carry direct labels.
 */
internal object KhaataPalette {

    // ---- Brand -------------------------------------------------------------------------------

    val Indigo10 = Color(0xFF10102E)
    val Indigo20 = Color(0xFF1B1B48)
    val Indigo30 = Color(0xFF2A2A6B)
    val Indigo40 = Color(0xFF3A3A8C)
    val Indigo50 = Color(0xFF4C4CAE)
    val Indigo60 = Color(0xFF6E6EC7)
    val Indigo70 = Color(0xFF9494DA)
    val Indigo80 = Color(0xFFBBBBEA)
    val Indigo90 = Color(0xFFDEDEF7)
    val Indigo95 = Color(0xFFEFEFFB)

    val Brass10 = Color(0xFF2A1C00)
    val Brass20 = Color(0xFF453100)
    val Brass30 = Color(0xFF624700)
    val Brass40 = Color(0xFF815F00)
    val Brass50 = Color(0xFFA17800)
    val Brass60 = Color(0xFFC29300)
    val Brass70 = Color(0xFFE0AF1F)
    val Brass80 = Color(0xFFF5CC5C)
    val Brass90 = Color(0xFFFFE49B)
    val Brass95 = Color(0xFFFFF2D4)

    // ---- Neutrals ----------------------------------------------------------------------------

    val Neutral0 = Color(0xFF000000)
    val Neutral6 = Color(0xFF0E0E14)
    val Neutral10 = Color(0xFF15151C)
    val Neutral15 = Color(0xFF1E1E27)
    val Neutral20 = Color(0xFF292933)
    val Neutral30 = Color(0xFF3F3F4B)
    val Neutral40 = Color(0xFF575765)
    val Neutral50 = Color(0xFF70707F)
    val Neutral60 = Color(0xFF8A8A99)
    val Neutral70 = Color(0xFFA5A5B3)
    val Neutral80 = Color(0xFFC2C2CD)
    val Neutral90 = Color(0xFFE1E1E8)
    val Neutral95 = Color(0xFFF1F1F5)
    val Neutral98 = Color(0xFFFAFAFC)
    val Neutral100 = Color(0xFFFFFFFF)

    // ---- Semantic money ----------------------------------------------------------------------

    /** Income and gains. */
    val Teal20 = Color(0xFF003731)
    val Teal30 = Color(0xFF005047)
    val Teal40 = Color(0xFF006B60)
    val Teal50 = Color(0xFF00877A)
    val Teal70 = Color(0xFF4EC7B6)
    val Teal80 = Color(0xFF7EE0D0)
    val Teal90 = Color(0xFFB6F2E8)

    /** Expenses and losses. */
    val Rose20 = Color(0xFF540A22)
    val Rose30 = Color(0xFF751433)
    val Rose40 = Color(0xFF9A2145)
    val Rose50 = Color(0xFFBC3B5E)
    val Rose70 = Color(0xFFEC8AA2)
    val Rose80 = Color(0xFFFFB1C1)
    val Rose90 = Color(0xFFFFD9E0)

    /** Warnings — a budget nearing its limit. */
    val Amber30 = Color(0xFF5C3D00)
    val Amber40 = Color(0xFF7D5400)
    val Amber70 = Color(0xFFE8B54A)
    val Amber90 = Color(0xFFFFE3B0)

    /** Errors — overspent, failed import. */
    val Red30 = Color(0xFF7B1D1D)
    val Red40 = Color(0xFFA02525)
    val Red70 = Color(0xFFF08D8D)
    val Red90 = Color(0xFFFFDAD6)

    /**
     * Categorical colours for charts, account cards and category chips.
     *
     * Ordered so adjacent entries differ in both hue and lightness, which keeps a pie chart
     * readable in greyscale and for a colour-blind viewer. Charts additionally label slices
     * directly rather than relying on a colour key.
     */
    val CategorySwatchesLight = listOf(
        Color(0xFF4C4CAE), // indigo
        Color(0xFF00877A), // teal
        Color(0xFFBC3B5E), // rose
        Color(0xFFA17800), // brass
        Color(0xFF5B6ABF), // periwinkle
        Color(0xFF2F7D5D), // moss
        Color(0xFF9C4F96), // plum
        Color(0xFFB5651D), // terracotta
        Color(0xFF3C7A96), // steel
        Color(0xFF7A5C3E), // walnut
        Color(0xFF6B7A2F), // olive
        Color(0xFF8C4A4A), // brick
    )

    val CategorySwatchesDark = listOf(
        Color(0xFF9494DA),
        Color(0xFF4EC7B6),
        Color(0xFFEC8AA2),
        Color(0xFFE0AF1F),
        Color(0xFF9AA6E8),
        Color(0xFF6FC79E),
        Color(0xFFD79BD1),
        Color(0xFFE9A268),
        Color(0xFF86BCD6),
        Color(0xFFC4A183),
        Color(0xFFB6C46F),
        Color(0xFFD79191),
    )
}

/**
 * Colours that carry meaning about money, resolved for the current theme.
 *
 * Kept outside Material's [androidx.compose.material3.ColorScheme] because they are semantic
 * rather than structural: "income" is not a Material role, and forcing it into `tertiary` would
 * make every call site cryptic.
 */
data class MoneyColors(
    val income: Color,
    val onIncomeContainer: Color,
    val incomeContainer: Color,
    val expense: Color,
    val onExpenseContainer: Color,
    val expenseContainer: Color,
    val warning: Color,
    val onWarningContainer: Color,
    val warningContainer: Color,
    val neutral: Color,
    val categorySwatches: List<Color>,
) {
    /** A stable colour for a category or account, from its seed. */
    fun swatch(seed: Int): Color = categorySwatches[Math.floorMod(seed, categorySwatches.size)]

    companion object {
        val Light = MoneyColors(
            income = KhaataPalette.Teal40,
            onIncomeContainer = KhaataPalette.Teal20,
            incomeContainer = KhaataPalette.Teal90,
            expense = KhaataPalette.Rose40,
            onExpenseContainer = KhaataPalette.Rose20,
            expenseContainer = KhaataPalette.Rose90,
            warning = KhaataPalette.Amber40,
            onWarningContainer = KhaataPalette.Amber30,
            warningContainer = KhaataPalette.Amber90,
            neutral = KhaataPalette.Neutral50,
            categorySwatches = KhaataPalette.CategorySwatchesLight,
        )

        val Dark = MoneyColors(
            income = KhaataPalette.Teal70,
            onIncomeContainer = KhaataPalette.Teal90,
            incomeContainer = KhaataPalette.Teal30,
            expense = KhaataPalette.Rose70,
            onExpenseContainer = KhaataPalette.Rose90,
            expenseContainer = KhaataPalette.Rose30,
            warning = KhaataPalette.Amber70,
            onWarningContainer = KhaataPalette.Amber90,
            warningContainer = KhaataPalette.Amber30,
            neutral = KhaataPalette.Neutral60,
            categorySwatches = KhaataPalette.CategorySwatchesDark,
        )
    }
}

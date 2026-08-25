package ai.labs32.khaata.core.common

/**
 * Reads the ISO-8601 durations the Play Store uses for trial and billing periods.
 *
 * `java.time.Period` cannot parse `P1W` and `java.time.Duration` cannot parse months or years, so
 * neither built-in covers what the store actually sends. This handles the simple single-unit forms
 * the store uses in practice and returns null for anything else, so an unexpected value produces
 * no badge rather than a wrong one.
 */
object IsoPeriod {

    private val SIMPLE = Regex("^P(\\d+)([DWMY])$")

    /**
     * Length in days, or null if [isoPeriod] is not a form we recognise.
     *
     * Months and years are approximated at 30 and 365 days. That is fine for the one thing this is
     * used for — writing "7 days free" on a plan card — and is never used in a money calculation.
     */
    fun days(isoPeriod: String?): Int? {
        val match = SIMPLE.find(isoPeriod?.trim()?.uppercase().orEmpty()) ?: return null
        val (amount, unit) = match.destructured
        val count = amount.toIntOrNull() ?: return null
        if (count <= 0) return null
        return when (unit) {
            "D" -> count
            "W" -> count * 7
            "M" -> count * 30
            "Y" -> count * 365
            else -> null
        }
    }
}

package com.mpesa.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class Horizon { TODAY, WEEK, MONTH }

/** How much the user can plan to spend over a day, a week or a month, and why. */
class Allowance(
    val horizon: Horizon,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val budget: Double?,          // what you can plan to spend over the whole period (null = not enough history)
    val spent: Double,            // spent so far in the period
    val left: Double?,            // budget - spent (never below 0)
    val perDayLeft: Double?,      // left / days remaining
    val daysLeft: Int,
    val typical: Double?,         // what you usually spend over a period of this length
    val incomeCeiling: Double?,   // 70% of your usual profit for this length of period
    val basis: String,
    val notes: List<String>,
)

/**
 * The budget advisor. "Living spend" = personal spending + loans, fees, cash and transfers out. Business restocking is kept apart,
 * because it is not money you can cut to feel better, and it comes back as sales.
 * Order of evidence: 1) your monthly budgets, spread over the days left; 2) otherwise 95% of what you usually spend;
 * and always a safety ceiling of 70% of your usual profit (a rule of thumb: keep 30% for restocking and savings).
 */
object Advisor {
    private fun date(c: Categorised) = c.tx.time?.toLocalDate()
    private fun isLiving(c: Categorised) = !c.tx.isInflow && ReviewEngine.flowOf(c.category, false).let { it == Flow.PERSONAL_SPEND || it == Flow.OTHER_OUT }
    private fun median(v: List<Double>): Double? = if (v.isEmpty()) null else v.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
    fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private fun kes(x: Double) = "KES " + "%,.0f".format(x)

    /** Totals of each of the last up to `n` complete weeks (Monday-Sunday) before the week of `ref`, from the first week with data. */
    private fun weekly(items: List<Categorised>, ref: LocalDate, n: Int, pick: (List<Categorised>) -> Double): List<Double> {
        val first = items.mapNotNull { date(it) }.minOrNull() ?: return emptyList()
        val last = items.mapNotNull { date(it) }.maxOrNull() ?: return emptyList()
        val limit = minOf(weekStart(ref), weekStart(last))                          // never count weeks after the data ends as "zero"
        var w = weekStart(first); if (w.isBefore(first)) w = w.plusWeeks(1)       // only weeks fully covered by the data
        val out = ArrayList<Double>()
        while (w.plusDays(6) < limit) {
            out.add(pick(items.filter { date(it)?.let { d -> !d.isBefore(w) && d <= w.plusDays(6) } == true })); w = w.plusWeeks(1)
        }
        return out.takeLast(n)
    }

    fun allowance(items: List<Categorised>, budgets: Map<String, Double>, ref: LocalDate, horizon: Horizon, future: Boolean = false): Allowance {
        val notes = ArrayList<String>()
        val (start, end) = when (horizon) {
            Horizon.TODAY -> (if (future) ref.plusDays(1) else ref).let { it to it }
            Horizon.WEEK -> weekStart(if (future) ref.plusWeeks(1) else ref).let { it to it.plusDays(6) }
            Horizon.MONTH -> (if (future) ref.plusMonths(1) else ref).let { it.withDayOfMonth(1) to it.withDayOfMonth(it.lengthOfMonth()) }
        }
        val living = items.filter { isLiving(it) }
        val spent = if (future) 0.0 else living.filter { date(it)?.let { d -> !d.isBefore(start) && !d.isAfter(ref) } == true }.sumOf { it.tx.amount }
        val today = if (future) start else ref
        val daysLeft = (ChronoUnit.DAYS.between(today, end) + 1).toInt().coerceAtLeast(1)

        // usual spend over a period of this length
        val weeklySpend = weekly(living, ref, 8) { r -> r.sumOf { it.tx.amount } }
        val typicalWeek = median(weeklySpend)
        val typical = typicalWeek?.let { when (horizon) { Horizon.WEEK -> it; Horizon.TODAY -> it / 7; Horizon.MONTH -> it * 30.0 / 7 } }

        // safety ceiling from usual profit
        val weeklyNet = weekly(items, ref, 8) { r -> r.filter { ReviewEngine.flowOf(it.category, it.tx.isInflow) == Flow.BUSINESS_INCOME }.sumOf { it.tx.amount } -
            r.filter { ReviewEngine.flowOf(it.category, it.tx.isInflow) == Flow.BUSINESS_EXPENSE }.sumOf { it.tx.amount } }
        val netWeek = median(weeklyNet)?.takeIf { it > 0 }
        val ceiling = netWeek?.let { it * 0.7 * when (horizon) { Horizon.WEEK -> 1.0; Horizon.TODAY -> 1.0 / 7; Horizon.MONTH -> 30.0 / 7 } }

        // 1) budgets, spread over the days left in the month
        val personal = budgets.filterKeys { !it.startsWith("Business") }
        var budget: Double? = null; var left: Double? = null; var perDay: Double? = null; var basis: String
        if (personal.isNotEmpty()) {
            val m0 = ref.withDayOfMonth(1)
            val monthSpent = living.filter { date(it)?.let { d -> !d.isBefore(m0) && !d.isAfter(ref) } == true }.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.tx.amount } }
            val remaining = personal.entries.sumOf { (c, lim) -> maxOf(0.0, lim - (monthSpent[c] ?: 0.0)) }
            val monthDaysLeft = (ChronoUnit.DAYS.between(ref, ref.withDayOfMonth(ref.lengthOfMonth())) + 1).toInt().coerceAtLeast(1)
            perDay = remaining / monthDaysLeft
            when (horizon) {
                Horizon.MONTH -> { budget = personal.values.sum(); left = if (future) budget else remaining }
                else -> { val l = perDay * daysLeft; left = l; budget = spent + l }
            }
            if (future && horizon != Horizon.MONTH) { budget = perDay * (if (horizon == Horizon.WEEK) 7 else 1); left = budget }
            basis = "your monthly budgets (KES %,.0f left this month, spread over the days remaining)".format(remaining)
        } else if (typical != null) {
            budget = typical * 0.95; left = maxOf(0.0, budget - spent); perDay = left / daysLeft
            basis = "95%% of what you usually spend (KES %,.0f for a period this long)".format(typical)
        } else {
            basis = "not enough history yet"
            notes.add("I need at least one full week of data to suggest a number. Import more, or set budgets on the Insights tab.")
        }

        if (budget != null && ceiling != null && budget > ceiling)
            notes.add("That is above 70% of your usual profit for this period (${kes(ceiling)}). Trimming a little would leave room for restocking.")
        if (budget != null && !future && spent > budget) notes.add("You have already spent more than that this ${horizon.name.lowercase()}.")
        else if (typical != null && !future && spent > typical) notes.add("You are already above your usual spending for this ${horizon.name.lowercase()} (${kes(typical)}).")
        if (ceiling == null && budget != null) notes.add("I could not see regular business profit, so I did not set an income-based ceiling.")
        return Allowance(horizon, start, end, budget, spent, left, perDay, daysLeft, typical, ceiling, basis, notes)
    }
}

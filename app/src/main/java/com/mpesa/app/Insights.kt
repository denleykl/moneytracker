package com.mpesa.app

import java.time.LocalDate
import java.time.YearMonth

/** Monthly spending limits per category, kept on the phone. */
interface BudgetStore {
    fun all(): Map<String, Double>
    fun set(category: String, amount: Double?)      // null removes the budget
}

class InMemoryBudgets(initial: Map<String, Double> = emptyMap()) : BudgetStore {
    private val map = LinkedHashMap(initial)
    override fun all(): Map<String, Double> = map
    override fun set(category: String, amount: Double?) { if (amount == null) map.remove(category) else map[category] = amount }
}

enum class Severity { ALERT, WARN, INFO, GOOD }
class Nudge(val severity: Severity, val title: String, val text: String, val category: String? = null)
class BudgetStatus(val category: String, val limit: Double, val spent: Double, val projected: Double) {
    val ratio: Double get() = if (limit > 0) spent / limit else 0.0
}

/** Rule-based nudges: simple, explainable arithmetic over the user's own history (no model needed). */
object Insights {
    private fun kes(x: Double) = "KES " + "%,.0f".format(x)
    private fun date(c: Categorised) = c.tx.time?.toLocalDate()
    private fun out(items: List<Categorised>, from: LocalDate, to: LocalDate) =
        items.filter { !it.tx.isInflow && date(it)?.let { d -> !d.isBefore(from) && !d.isAfter(to) } == true }
    private fun any(items: List<Categorised>, from: LocalDate, to: LocalDate) =
        items.filter { date(it)?.let { d -> !d.isBefore(from) && !d.isAfter(to) } == true }

    /** How each budget is doing this month (the month of `ref`), with an end-of-month projection. */
    fun budgetStatus(items: List<Categorised>, budgets: Map<String, Double>, ref: LocalDate): List<BudgetStatus> {
        val first = ref.withDayOfMonth(1)
        val spent = out(items, first, ref).groupBy { it.category }.mapValues { e -> e.value.sumOf { it.tx.amount } }
        val days = ref.dayOfMonth.toDouble(); val len = ref.lengthOfMonth().toDouble()
        return budgets.map { (cat, limit) ->
            val s = spent[cat] ?: 0.0
            BudgetStatus(cat, limit, s, s / days * len)
        }.sortedByDescending { it.ratio }
    }

    /** Suggested limits: about 90% of what you usually spend per month on each category (last 3 full months, else this month's pace). */
    fun suggestBudgets(items: List<Categorised>, ref: LocalDate): Map<String, Double> {
        val skip = setOf("Savings, Loans & Fuliza", "Person-to-Person")
        val thisMonth = YearMonth.from(ref)
        val spend = items.filter { !it.tx.isInflow && it.category !in skip && date(it) != null }
        val months = spend.map { YearMonth.from(date(it)!!) }.filter { it < thisMonth }.distinct().sortedDescending().take(3)
        val perCat: Map<String, Double> = if (months.isNotEmpty()) {
            spend.filter { YearMonth.from(date(it)!!) in months }.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.tx.amount } / months.size }
        } else {
            val days = ref.dayOfMonth.toDouble(); val len = ref.lengthOfMonth().toDouble()
            spend.filter { YearMonth.from(date(it)!!) == thisMonth }.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.tx.amount } / days * len }
        }
        return perCat.mapValues { Math.round(it.value * 0.9 / 100.0) * 100.0 }.filterValues { it >= 500.0 }
    }

    fun nudges(items: List<Categorised>, budgets: Map<String, Double>, ref: LocalDate): List<Nudge> {
        val res = ArrayList<Nudge>()

        // 1. budgets
        for (b in budgetStatus(items, budgets, ref)) when {
            b.ratio >= 1.0 -> res.add(Nudge(Severity.ALERT, "Over budget: ${b.category}", "${kes(b.spent)} spent of ${kes(b.limit)} this month.", b.category))
            b.ratio >= 0.8 -> res.add(Nudge(Severity.WARN, "Close to your limit: ${b.category}", "${kes(b.spent)} of ${kes(b.limit)} used (${"%.0f".format(b.ratio * 100)}%).", b.category))
            b.projected > b.limit && b.spent > 0 -> res.add(Nudge(Severity.INFO, "On track to go over: ${b.category}",
                "At this pace you will spend about ${kes(b.projected)} this month; the limit is ${kes(b.limit)}.", b.category))
        }

        val last30 = ref.minusDays(29)
        val recent = out(items, last30, ref)

        // 2. spikes: last 30 days vs the 30 before (only when the history is long enough)
        val earliest = items.mapNotNull { date(it) }.minOrNull()
        if (earliest != null && !earliest.isAfter(ref.minusDays(44))) {
            val prior = out(items, ref.minusDays(59), ref.minusDays(30)).groupBy { it.category }.mapValues { e -> e.value.sumOf { it.tx.amount } }
            for ((cat, now) in recent.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.tx.amount } }) {
                val before = prior[cat] ?: 0.0
                if (before > 0 && now >= before * 1.4 && now - before >= 300)
                    res.add(Nudge(Severity.WARN, "Spending jumped: $cat", "${kes(now)} in the last 30 days, up ${"%.0f".format((now / before - 1) * 100)}% from ${kes(before)} before.", cat))
            }
        }

        // 3. many small payments to the same place
        recent.filter { it.tx.txGroup == "merchant" || it.tx.txGroup == "paybill" }.groupBy { it.counterparty }
            .filterValues { it.size >= 6 && it.sumOf { r -> r.tx.amount } / it.size < 300 }
            .entries.sortedByDescending { e -> e.value.sumOf { it.tx.amount } }.take(2).forEach { (_, rows) ->
                val total = rows.sumOf { it.tx.amount }
                res.add(Nudge(Severity.INFO, "Small payments add up", "${rows.size} payments to ${Counterparty.displayName(rows[0].tx)} in 30 days came to ${kes(total)} (about ${kes(total / rows.size)} each).", rows[0].category))
            }

        // 4. business health
        val rows30 = any(items, last30, ref)
        val inc = rows30.filter { ReviewEngine.flowOf(it.category, it.tx.isInflow) == Flow.BUSINESS_INCOME }.sumOf { it.tx.amount }
        val exp = rows30.filter { ReviewEngine.flowOf(it.category, it.tx.isInflow) == Flow.BUSINESS_EXPENSE }.sumOf { it.tx.amount }
        if (exp > 0 && exp > inc) res.add(Nudge(Severity.ALERT, "Business spending is above income", "Last 30 days: ${kes(exp)} out vs ${kes(inc)} in."))
        else if (inc > 0) res.add(Nudge(Severity.GOOD, "Business is in the black", "Last 30 days: ${kes(inc)} in, ${kes(exp)} out, net ${kes(inc - exp)}."))

        // 5. a concrete saving idea
        val skipCut = setOf("Savings, Loans & Fuliza", "Fees & Charges", "Cash Withdrawal", "Person-to-Person", "Personal - Bills & Utilities")
        val byCat = recent.filter { it.category.startsWith("Personal") && it.category !in skipCut }.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.tx.amount } }
        val pick = byCat["Personal - Lifestyle & Entertainment"]?.takeIf { it >= 500 }?.let { "Personal - Lifestyle & Entertainment" to it } ?: byCat.maxByOrNull { it.value }?.takeIf { it.value >= 500 }?.toPair()
        if (pick != null) res.add(Nudge(Severity.INFO, "A small cut would help", "Spending 20% less on ${pick.first} would save about ${kes(Math.round(pick.second * 0.2 / 10.0) * 10.0)} a month.", pick.first))

        return res.sortedBy { it.severity.ordinal }
    }
}

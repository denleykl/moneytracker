package com.mpesa.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

class Answer(val text: String, val details: List<String> = emptyList())

enum class Metric { SUM, COUNT, AVG, MAX, MIN, RANK, NET, CUSTOMERS, ADVICE, STATUS, REVIEW, PRICES, COMPARE }
enum class Dir { OUT, IN }
enum class GroupBy { NONE, CATEGORY, COUNTERPARTY, DAY, WEEKDAY, MONTH }

/** A question turned into something a computer can run. Built by the keyword planner below, or by an optional language model (see Ask.PLAN_PROMPT). */
class QueryPlan(
    val metric: Metric,
    val dir: Dir = Dir.OUT,
    val dirExplicit: Boolean = true,
    val range: DateRange,
    val label: String,
    val periodFound: Boolean = true,
    val horizon: Horizon? = null,
    val future: Boolean = false,
    val categories: Set<String> = emptySet(),
    val bucket: String? = null,
    val name: String? = null,
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    val groupBy: GroupBy = GroupBy.NONE,
    val perUnit: String? = null,
    val minStrict: Boolean = false,   // "over 1000" excludes 1000; "at least 1000" includes it
    val maxStrict: Boolean = false,
)

/**
 * "Ask your data" in two steps: (1) PLAN: read the question and decide what is being asked (a period, money in or out, a category or shop,
 * an amount limit, and the kind of question: total, how many, average, biggest, who most, compare, budget advice...), tolerating typos;
 * (2) EXECUTE the plan on the user's own transactions. All on the phone. A language model can replace step 1 for unusual wording
 * and send back a JSON plan, and step 2 stays the same: the model never sees any transactions.
 */
object Ask {
    val suggestions = listOf("How much should I budget this week?", "How much can I spend today?", "Compare this week with last week",
        "Who did I pay most?", "How many customers this month?", "What are my usual prices?", "Business income this month",
        "Which day do I spend most?", "What did I spend on food last week?", "Net business last 30 days")

    private val categoryWords = listOf(
        listOf("food", "groceries", "grocery", "household", "restaurant", "eating", "lunch", "dinner", "supper", "breakfast", "snacks", "supermarket", "shopping") to "Personal - Food & Household",
        listOf("bill", "bills", "utilities", "airtime", "bundles", "electricity", "kplc", "tokens", "wifi", "internet", "water", "rent") to "Personal - Bills & Utilities",
        listOf("betting", "bet", "entertainment", "lifestyle", "drinks", "alcohol", "pub", "bar", "gambling", "fun") to "Personal - Lifestyle & Entertainment",
        listOf("fee", "fees", "charges", "charge") to "Fees & Charges",
        listOf("loan", "loans", "fuliza", "savings", "shwari", "debt") to "Savings, Loans & Fuliza",
        listOf("cash", "withdraw", "withdrawal", "withdrawals", "atm") to "Cash Withdrawal",
        listOf("stock", "inventory", "supplies", "restock", "restocking", "goods", "wholesale") to "Business - Stock",
        listOf("operating", "fuel", "transport", "fare", "matatu", "logistics") to "Business - Operating costs",
    )
    private val inWords = setOf("received", "receive", "income", "earned", "earn", "earnings", "sales", "sale", "sold", "made", "make", "came", "profit")
    private val months = Month.values().associateBy { it.name.lowercase() }
    private val weekdays = DayOfWeek.values().associateBy { it.name.lowercase() }
    private val stop = setOf("MONTH", "WEEK", "TODAY", "LAST", "THIS", "MUCH", "SPENT", "SPEND", "MONEY", "WHAT", "WHICH", "FROM", "WITH", "TOTAL", "INCOME",
        "BUSINESS", "PERSONAL", "DAYS", "MONTHS", "YEAR", "NET", "PROFIT", "MOST", "BIGGEST", "LARGEST", "EXPENSE", "PAID", "PAYMENTS", "TRANSACTIONS", "BUDGET", "CUSTOMERS")
    private val vocab: Set<String> by lazy {
        (categoryWords.flatMap { it.first } + inWords + months.keys + weekdays.keys + listOf("budget", "allowance", "customers", "customer", "compare", "average", "biggest", "largest",
            "highest", "smallest", "lowest", "yesterday", "weekend", "spend", "spent", "transactions", "payments", "expense", "expenses", "business", "personal", "profit", "month", "months",
            "weekly", "daily", "usual", "prices", "afford", "review", "unsure", "overspending", "income", "category", "categories", "difference", "versus")).toSet()
    }

    // ---------------- helpers ----------------
    private fun kes(x: Double) = "KES " + "%,.0f".format(x)
    private fun name(c: Categorised) = Counterparty.displayName(c.tx).ifBlank { c.category }
    private fun lev(a: String, b: String): Int {
        var pp = IntArray(b.length + 1); var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) cur[j] = minOf(cur[j], pp[j - 2] + 1)
            }
            pp = prev; prev = cur
        }
        return prev[b.length]
    }
    /** corrects a mistyped word to the closest known word (same first letter, 1 edit, or 2 for long words) */
    private fun fix(w: String, known: Collection<String>): String {
        if (w.length < 5 || w in known) return w
        val max = if (w.length >= 8) 2 else 1
        return known.filter { it.length >= 4 && it[0] == w[0] && lev(w, it) <= max }.minByOrNull { lev(w, it) } ?: w
    }

    private class Per(val range: DateRange, val label: String, val found: Boolean, val unit: Horizon? = null, val future: Boolean = false)

    private fun period(q: String, ref: LocalDate): Per {
        val monday = Advisor.weekStart(ref)
        Regex("(?:last|past|previous) (\\d+) (day|week|month)s?").find(q)?.let { m ->
            val n = m.groupValues[1].toLong(); val u = m.groupValues[2]
            val from = when (u) { "day" -> ref.minusDays(n - 1); "week" -> ref.minusWeeks(n).plusDays(1); else -> ref.minusMonths(n).plusDays(1) }
            return Per(DateRange(from, ref), "in the last $n ${u}s", true)
        }
        fun has(s: String) = Regex("\\b$s\\b").containsMatchIn(q)
        return when {
            has("next week") -> Per(DateRange(monday.plusWeeks(1), monday.plusDays(13)), "next week", true, Horizon.WEEK, true)
            has("next month") -> { val f = ref.withDayOfMonth(1).plusMonths(1); Per(DateRange(f, f.withDayOfMonth(f.lengthOfMonth())), "next month", true, Horizon.MONTH, true) }
            has("tomorrow") -> Per(DateRange(ref.plusDays(1), ref.plusDays(1)), "tomorrow", true, Horizon.TODAY, true)
            has("yesterday") -> Per(DateRange(ref.minusDays(1), ref.minusDays(1)), "yesterday", true)
            has("today") || has("tonight") -> Per(DateRange(ref, ref), "today", true, Horizon.TODAY)
            has("last week") || has("previous week") -> Per(DateRange(monday.minusWeeks(1), monday.minusDays(1)), "last week", true)
            has("this week") -> Per(DateRange(monday, ref), "this week", true, Horizon.WEEK)
            has("last month") || has("previous month") -> { val f = ref.withDayOfMonth(1).minusMonths(1); Per(DateRange(f, f.withDayOfMonth(f.lengthOfMonth())), "last month", true) }
            has("this month") -> Per(DateRange(ref.withDayOfMonth(1), ref), "this month", true, Horizon.MONTH)
            has("last year") -> Per(DateRange(ref.withDayOfYear(1).minusYears(1), ref.withDayOfYear(1).minusDays(1)), "last year", true)
            has("this year") -> Per(DateRange(ref.withDayOfYear(1), ref), "this year", true)
            has("weekend") -> { val sat = ref.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)); Per(DateRange(sat, sat.plusDays(1)), "last weekend", true) }
            Regex("all time|overall|\\bever\\b|in total").containsMatchIn(q) -> Per(DateRange(null, null), "overall", true)
            has("recently") || has("lately") -> Per(DateRange(ref.minusDays(29), ref), "in the last 30 days", true)
            else -> {
                months.entries.firstOrNull { (n, _) -> if (n == "may") Regex("\\b(in|during|for|of) may\\b").containsMatchIn(q) else has(n) }?.let { (_, m) ->
                    val y = if (m.value > ref.monthValue) ref.year - 1 else ref.year
                    val f = LocalDate.of(y, m, 1)
                    return Per(DateRange(f, f.withDayOfMonth(f.lengthOfMonth())), "in ${m.name.lowercase().replaceFirstChar { it.uppercase() }}", true)
                }
                weekdays.entries.firstOrNull { (n, _) -> has(n) }?.let { (_, d) ->
                    val day = ref.with(TemporalAdjusters.previousOrSame(d))
                    return Per(DateRange(day, day), "on ${d.name.lowercase().replaceFirstChar { it.uppercase() }}", true)
                }
                if (Regex("\\bweek(ly)?\\b").containsMatchIn(q)) Per(DateRange(monday, ref), "this week", true, Horizon.WEEK)
                else Per(DateRange(ref.withDayOfMonth(1), ref), "this month", false)
            }
        }
    }

    private fun amount(s: String): Double? = Regex("(\\d[\\d,]*(?:\\.\\d+)?)\\s*(k)?").find(s)?.let { m ->
        (m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null) * (if (m.groupValues[2] == "k") 1000 else 1)
    }

    // ---------------- step 1: PLAN ----------------
    fun plan(question: String, items: List<Categorised>, ref: LocalDate): QueryPlan? {
        val q0 = question.lowercase().replace('’', '\'').trim()
        if (q0.isEmpty()) return null
        val nameTokens = items.flatMap { Similar.tokens(Counterparty.displayName(it.tx)) }.filter { it.length >= 4 && it !in stop }.toSet()
        val nameLower = nameTokens.map { it.lowercase() }
        val q = Regex("[a-z']+").replace(q0) { m -> fix(m.value, vocab).let { v -> if (v == m.value) fix(m.value, nameLower) else v } }
        val fixedWords = Regex("[a-z']+").findAll(q).map { it.value }.toList()
        val words = fixedWords.toSet()
        fun has(vararg ps: String) = ps.any { p -> if (p.contains(' ')) p in q else p in words }
        val per = period(q, ref)

        // filters
        val cat = categoryWords.firstOrNull { (ws, _) -> ws.any { it in words } }?.second
        val bucket = when { has("business", "sales", "sale", "customers", "customer", "restock", "restocking") -> "Business"; "personal" in words -> "Personal"; else -> null }
        val catWordSet = categoryWords.flatMap { it.first }.map { it.uppercase() }.toSet()
        val nameHit = words.map { it.uppercase() }.filter { it in nameTokens && it !in catWordSet }.maxByOrNull { it.length }
        var minA: Double? = null; var maxA: Double? = null; var minStrict = false; var maxStrict = false
        Regex("between\\s+(?:kes|ksh)?\\s*(\\d[\\d,.]*k?)\\s+and\\s+(?:kes|ksh)?\\s*(\\d[\\d,.]*k?)").find(q)?.let { minA = amount(it.groupValues[1]); maxA = amount(it.groupValues[2]) }
        Regex("(over|above|more than|greater than|at least|exceeding|bigger than)\\s+(?:kes|ksh)?\\s*(\\d[\\d,.]*k?)").find(q)?.let { minA = amount(it.groupValues[2]); minStrict = it.groupValues[1] != "at least" }
        Regex("(under|below|less than|at most|smaller than|cheaper than)\\s+(?:kes|ksh)?\\s*(\\d[\\d,.]*k?)").find(q)?.let { maxA = amount(it.groupValues[2]); maxStrict = it.groupValues[1] != "at most" }
        val inflow = words.any { it in inWords } && !has("profit") || has("money in", "paid me", "sent me", "who paid me", "came in", "coming in") || (has("customers") && has("paid"))
        val dirExplicit = inflow || has("spent", "spend", "paid", "pay", "cost", "expense", "expenses", "bought", "outgoing", "sent")
        val dir = if (inflow) Dir.IN else Dir.OUT
        val pFound = per.found
        // a comparison always compares THIS week/month with the one before, even if the sentence also says "last week"
        val cmp = when { Regex("\\bweek\\b").containsMatchIn(q) -> period("this week", ref); Regex("\\bmonth\\b").containsMatchIn(q) -> period("this month", ref); else -> per }
        fun mk(m: Metric, group: GroupBy = GroupBy.NONE, perUnit: String? = null, horizon: Horizon? = null, p2: Per = per) = QueryPlan(m, dir, dirExplicit, p2.range, p2.label, p2.found,
            horizon ?: p2.unit, p2.future, setOfNotNull(cat), bucket, nameHit, minA, maxA, group, perUnit, minStrict, maxStrict)

        // kind of question, most specific first
        val statusPhrases = arrayOf("on track", "over budget", "within budget", "under budget", "budget status", "overspending", "overspend", "how are my budgets", "how am i doing", "how is my budget", "too much")
        val advicePhrases = arrayOf("budget", "allowance", "afford", "allocate", "can i spend", "should i spend", "safe to spend", "left to spend", "money left", "have left", "how much left", "enough money", "spending money", "can i use")
        return when {
            has(*statusPhrases) -> mk(Metric.STATUS)
            has(*advicePhrases) -> mk(Metric.ADVICE, horizon = per.unit ?: if (has("day", "daily")) Horizon.TODAY else Horizon.WEEK)
            Regex("(more|less|higher|lower|bigger|smaller|up|down)\\s+than\\s+(last|previous|before)|compare|compared|versus|\\bvs\\b|difference between|changed|trend").containsMatchIn(q) -> mk(Metric.COMPARE, p2 = cmp)
            has("review", "unsure", "uncertain", "not sure") -> mk(Metric.REVIEW)
            has("price range", "price bands", "usual prices", "my prices", "typical sale", "what do i sell", "sale prices", "prices") -> mk(Metric.PRICES)
            has("how many") && has("customers", "customer", "clients", "buyers", "people") -> mk(Metric.CUSTOMERS)
            has("customers", "clients", "buyers") && !has("most", "top") -> mk(Metric.CUSTOMERS)
            (has("who") && has("most", "top", "biggest", "largest", "highest")) || (has("top") && has("merchants", "payees", "shops", "people", "customers")) -> mk(Metric.RANK, GroupBy.COUNTERPARTY)
            has("spend most", "spent most", "spend the most", "where do i spend", "biggest category", "what do i spend", "most on") && has("day", "days", "weekday") -> mk(Metric.RANK, GroupBy.WEEKDAY)
            has("which day", "what day", "busiest day") -> mk(Metric.RANK, GroupBy.WEEKDAY)
            has("spend most", "spent most", "spend the most", "where do i spend", "biggest category", "what do i spend", "most on", "category", "categories") -> mk(Metric.RANK, GroupBy.CATEGORY)
            has("biggest", "largest", "highest", "most expensive", "maximum") -> mk(Metric.MAX)
            has("smallest", "lowest", "cheapest", "minimum") -> mk(Metric.MIN)
            has("net", "loss", "margin") || (has("profit") && !has("average")) -> mk(Metric.NET)
            has("per day", "a day", "each day", "daily", "every day") -> mk(Metric.SUM, perUnit = "day")
            has("per week", "a week", "each week", "weekly", "every week") && !has("budget") -> mk(Metric.SUM, perUnit = "week")
            has("average", "avg", "mean", "typical", "usually") -> mk(Metric.AVG)
            has("how many", "number of", "count", "how often") -> mk(Metric.COUNT)
            has("spend", "spent", "paid", "pay", "cost", "expense", "expenses", "how much", "money", "total", "bought", "made", "make", "earned") || cat != null || bucket != null || nameHit != null ||
                inflow || minA != null || maxA != null || pFound -> mk(Metric.SUM)
            else -> null
        }
    }

    // ---------------- step 2: EXECUTE ----------------
    private fun select(items: List<Categorised>, p: QueryPlan, useDir: Boolean = true): List<Categorised> {
        var r = Periods.filter(items, p.range)
        if (p.categories.isNotEmpty()) r = r.filter { it.category in p.categories }
        p.bucket?.let { b -> r = r.filter { it.category.startsWith(b) } }
        p.name?.let { n -> r = r.filter { Counterparty.displayName(it.tx).uppercase().contains(n.uppercase()) } }
        p.minAmount?.let { m -> r = r.filter { if (p.minStrict) it.tx.amount > m else it.tx.amount >= m } }
        p.maxAmount?.let { m -> r = r.filter { if (p.maxStrict) it.tx.amount < m else it.tx.amount <= m } }
        if (useDir) r = r.filter { it.tx.isInflow == (p.dir == Dir.IN) }
        return r
    }
    private fun what(p: QueryPlan): String? {
        val base = listOfNotNull(p.categories.joinToString(" / ").ifEmpty { null } ?: p.bucket?.lowercase(),
            p.name?.lowercase()?.replaceFirstChar { it.uppercase() }).joinToString(" / ").ifEmpty { null }
        val amt = listOfNotNull(p.minAmount?.let { "${if (p.minStrict) "over" else "at least"} ${kes(it)}" },
            p.maxAmount?.let { "${if (p.maxStrict) "under" else "at most"} ${kes(it)}" }).joinToString(" and ").ifEmpty { null }
        return when { base != null && amt != null -> "$base (payments $amt)"; base != null -> base; amt != null -> "payments $amt"; else -> null }
    }
    private fun top(list: List<Categorised>) = list.groupBy { it.counterparty }.entries.sortedByDescending { e -> e.value.sumOf { it.tx.amount } }.take(3)
        .map { (_, r) -> "${name(r[0])}: ${kes(r.sumOf { it.tx.amount })} (${r.size})" }
    private fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"

    fun execute(p: QueryPlan, items: List<Categorised>, ref: LocalDate, budgets: Map<String, Double> = emptyMap(), business: BusinessProfile? = null): Answer {
        val w = what(p); val verb = if (p.dir == Dir.IN) "received" else "spent"
        val rows = select(items, p)
        return when (p.metric) {
            Metric.ADVICE -> {
                val a = Advisor.allowance(items, budgets, ref, p.horizon ?: Horizon.WEEK, p.future)
                val per = when (a.horizon) { Horizon.WEEK -> if (p.future) "next week" else "this week"; Horizon.TODAY -> if (p.future) "tomorrow" else "today"; Horizon.MONTH -> if (p.future) "next month" else "this month" }
                if (a.budget == null) Answer("I cannot suggest a budget for $per yet.", listOf("Basis: ${a.basis}") + a.notes)
                else Answer(
                    if (p.future) "A sensible budget for $per is about ${kes(a.budget)}${a.perDayLeft?.let { " (around ${kes(it)} a day)" } ?: ""}."
                    else if (a.spent > a.budget) "Your budget for $per is about ${kes(a.budget)}, but you have already spent ${kes(a.spent)} (${kes(a.spent - a.budget)} over)."
                    else "Your budget for $per is about ${kes(a.budget)}. You have spent ${kes(a.spent)} so far, which leaves about ${kes(a.left ?: 0.0)}" +
                        (if (a.horizon != Horizon.TODAY) " (around ${kes(a.perDayLeft ?: 0.0)} a day for the next ${plural(a.daysLeft, "day")})." else "."),
                    listOf("Based on: ${a.basis}") + (a.typical?.let { listOf("You usually spend about ${kes(it)} over a period this long.") } ?: emptyList()) +
                        (a.incomeCeiling?.let { listOf("Safety ceiling from your usual profit: ${kes(it)}.") } ?: emptyList()) + a.notes)
            }
            Metric.STATUS -> {
                val st = Insights.budgetStatus(items, budgets, ref)
                if (st.isEmpty()) Answer("You have no budgets yet.", listOf("Open the Insights tab and tap Suggest budgets for a starting point based on your own spending."))
                else {
                    val over = st.count { it.ratio >= 1.0 }; val close = st.count { it.ratio in 0.8..0.9999 }
                    Answer(if (over > 0) "You are over budget in $over categor${if (over == 1) "y" else "ies"} this month." else if (close > 0) "You are close to your limit in $close categor${if (close == 1) "y" else "ies"}, none over." else "You are within all your budgets this month.",
                        st.take(5).map { "${it.category}: ${kes(it.spent)} of ${kes(it.limit)} (${"%.0f".format(it.ratio * 100)}%)" })
                }
            }
            Metric.PRICES -> {
                if (business == null || business.isEmpty) Answer("I have not found a clear sale price range yet.", listOf("I need at least 8 payments from 3 different people at similar amounts, or you can type your own prices on the Insights tab."))
                else Answer("Your usual sale prices: " + business.bands.joinToString(" and ") { it.label } + ".",
                    business.bands.map { b -> "${b.label}: " + if (b.manual) "typed in by you" else "${b.count} payments from ${b.senders} different people in the last ${business.lookbackDays} days" })
            }
            Metric.REVIEW -> { val n = Periods.filter(items, p.range).count { it.needsReview }; Answer("${plural(n, "transaction")} ${p.label} still need your answer.") }
            Metric.NET -> {
                val r = if (p.periodFound) Periods.filter(items, p.range) else Periods.filter(items, DateRange(ref.minusDays(29), ref))
                val inc = r.filter { ReviewEngine.flowOf(it.category, it.tx.isInflow) == Flow.BUSINESS_INCOME }.sumOf { it.tx.amount }
                val exp = r.filter { ReviewEngine.flowOf(it.category, it.tx.isInflow) == Flow.BUSINESS_EXPENSE }.sumOf { it.tx.amount }
                Answer("Business net ${if (p.periodFound) p.label else "in the last 30 days"}: ${kes(inc - exp)}.", listOf("Business income: ${kes(inc)}", "Business expenses: ${kes(exp)}"))
            }
            Metric.CUSTOMERS -> {
                val r = select(items, p, useDir = false).filter { it.tx.isInflow && (it.tx.txGroup == "person_in" || it.category.startsWith("Business")) }
                val people = r.groupBy { it.counterparty }
                if (people.isEmpty()) Answer("I found no customer payments ${p.label}.")
                else Answer("${plural(people.size, "different person").replace("persons", "people")} paid you ${p.label}: ${plural(r.size, "payment")}, ${kes(r.sumOf { it.tx.amount })} in total.", top(r).map { "Top payer: $it" }.take(3))
            }
            Metric.COMPARE -> {
                val f = p.range.from
                if (f == null) Answer("Tell me which two periods to compare, for example: compare this week with last week.")
                else {
                    val t = p.range.to ?: ref
                    val (prev, phrase) = when {
                        p.label.endsWith("week") -> DateRange(f.minusWeeks(1), t.minusWeeks(1)) to "in the same days of the week before"
                        p.label.endsWith("month") -> DateRange(f.minusMonths(1), t.minusMonths(1)) to "in the same part of the month before"
                        p.label == "today" || p.label == "yesterday" -> DateRange(f.minusDays(1), t.minusDays(1)) to "the day before"
                        else -> { val len = ChronoUnit.DAYS.between(f, t) + 1; DateRange(f.minusDays(len), f.minusDays(1)) to "in the $len days before" }
                    }
                    val pp = QueryPlan(p.metric, p.dir, p.dirExplicit, prev, "", true, null, false, p.categories, p.bucket, p.name, p.minAmount, p.maxAmount, minStrict = p.minStrict, maxStrict = p.maxStrict)
                    val nowRows = select(items, p); val prevRows = select(items, pp)
                    val now = nowRows.sumOf { it.tx.amount }; val before = prevRows.sumOf { it.tx.amount }
                    val nowCat = nowRows.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.tx.amount } }
                    val prevCat = prevRows.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.tx.amount } }
                    val moves = (nowCat.keys + prevCat.keys).map { it to (nowCat[it] ?: 0.0) - (prevCat[it] ?: 0.0) }.filter { it.second != 0.0 }
                        .sortedByDescending { Math.abs(it.second) }.take(3).map { (c, d) -> "$c: ${if (d >= 0) "+" else "-"}${kes(Math.abs(d))}" }
                    val head = if (before == 0.0) "You $verb ${kes(now)} ${p.label}; there is nothing $phrase to compare with."
                    else "You $verb ${kes(now)} ${p.label}, ${if (now >= before) "up" else "down"} ${"%.0f".format(Math.abs(now / before - 1) * 100)}% from ${kes(before)} $phrase."
                    Answer(head, moves)
                }
            }
            Metric.RANK -> {
                if (rows.isEmpty()) return Answer("I found nothing ${p.label}.")
                when (p.groupBy) {
                    GroupBy.CATEGORY -> {
                        val g = rows.groupBy { it.category }.entries.sortedByDescending { e -> e.value.sumOf { it.tx.amount } }
                        val total = rows.sumOf { it.tx.amount }
                        Answer("You ${if (p.dir == Dir.IN) "receive" else "spend"} most on ${g[0].key} ${p.label}: ${kes(g[0].value.sumOf { it.tx.amount })} (${"%.0f".format(g[0].value.sumOf { it.tx.amount } / total * 100)}%).",
                            g.take(5).map { (c, r) -> "$c: ${kes(r.sumOf { it.tx.amount })} (${r.size})" })
                    }
                    GroupBy.WEEKDAY, GroupBy.DAY, GroupBy.MONTH -> {
                        val key: (Categorised) -> String = { when (p.groupBy) { GroupBy.WEEKDAY -> it.tx.time?.dayOfWeek?.name?.lowercase()?.replaceFirstChar { c -> c.uppercase() } ?: "?"; GroupBy.DAY -> it.tx.time?.toLocalDate()?.toString() ?: "?"; else -> it.tx.time?.let { t -> "${t.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }} ${t.year}" } ?: "?" } }
                        val g = rows.groupBy(key).entries.sortedByDescending { e -> e.value.sumOf { it.tx.amount } }
                        Answer("${if (p.dir == Dir.IN) "Most money comes in" else "You spend most"} on ${g[0].key}${if (p.groupBy == GroupBy.WEEKDAY) "s" else ""} ${p.label}: ${kes(g[0].value.sumOf { it.tx.amount })} (${plural(g[0].value.size, "transaction")}).",
                            g.take(5).map { (k, r) -> "$k: ${kes(r.sumOf { it.tx.amount })} (${r.size})" })
                    }
                    else -> {
                        val g = rows.groupBy { it.counterparty }.entries.sortedByDescending { e -> e.value.sumOf { it.tx.amount } }.take(5)
                        Answer("${if (p.dir == Dir.IN) "Most money came from" else "You paid most to"} ${name(g[0].value[0])} ${p.label}: ${kes(g[0].value.sumOf { it.tx.amount })}.",
                            g.map { (_, r) -> "${name(r[0])}: ${kes(r.sumOf { it.tx.amount })} (${plural(r.size, "transaction")})" })
                    }
                }
            }
            Metric.MAX, Metric.MIN -> {
                val m = if (p.metric == Metric.MAX) rows.maxByOrNull { it.tx.amount } else rows.minByOrNull { it.tx.amount }
                if (m == null) Answer("I found nothing ${p.label}.")
                else Answer("Your ${if (p.metric == Metric.MAX) "biggest" else "smallest"} ${if (p.dir == Dir.IN) "payment received" else "expense"} ${p.label} was ${kes(m.tx.amount)} ${if (p.dir == Dir.IN) "from" else "to"} ${name(m)}" +
                    (m.tx.time?.let { " on ${it.toLocalDate()}" } ?: "") + ".", listOf("Category: ${m.category}"))
            }
            Metric.COUNT -> {
                val list = if (p.dirExplicit) rows else select(items, p, useDir = false)
                Answer("${plural(list.size, "transaction")}${w?.let { " ($it)" } ?: ""} ${p.label}.", top(list))
            }
            Metric.AVG -> {
                if (rows.isEmpty()) Answer("I found nothing ${p.label}.")
                else Answer("Your average ${if (p.dir == Dir.IN) "payment received" else "payment"}${w?.let { " ($it)" } ?: ""} ${p.label} is ${kes(rows.sumOf { it.tx.amount } / rows.size)} across ${plural(rows.size, "transaction")}.", top(rows))
            }
            Metric.SUM -> {
                if (rows.isEmpty()) return Answer("I found no ${if (p.dir == Dir.IN) "money in" else "spending"}${w?.let { " for $it" } ?: ""} ${p.label}.")
                val total = rows.sumOf { it.tx.amount }
                if (p.perUnit != null) {
                    val first = (p.range.from ?: items.mapNotNull { it.tx.time?.toLocalDate() }.minOrNull() ?: ref)
                    val days = (ChronoUnit.DAYS.between(first, minOf(p.range.to ?: ref, ref)) + 1).coerceAtLeast(1)
                    val per = if (p.perUnit == "week") total / days * 7 else total / days
                    Answer("You $verb about ${kes(per)} a ${p.perUnit}${w?.let { " on $it" } ?: ""} ${p.label} (${kes(total)} over ${plural(days.toInt(), "day")}).", top(rows))
                } else Answer("You $verb ${kes(total)}${w?.let { if (p.dir == Dir.IN) " ($it)" else " on $it" } ?: ""} ${p.label} across ${plural(rows.size, "transaction")}.", top(rows))
            }
        }
    }

    /** The one call the app makes. A planner that cannot understand returns a polite hint. */
    fun answer(question: String, items: List<Categorised>, ref: LocalDate, budgets: Map<String, Double> = emptyMap(), business: BusinessProfile? = null): Answer {
        if (question.isBlank()) return Answer("Ask me something about your money, for example: ${suggestions[0]}")
        val p = plan(question, items, ref) ?: return Answer("I did not understand that. Try: ${suggestions.take(3).joinToString("  |  ")}")
        return execute(p, items, ref, budgets, business)
    }

    // ---------------- optional language-model planner ----------------
    /** Send this (plus the question) to a language model, then pass its JSON reply to fromJson(). No transactions are ever sent. */
    val PLAN_PROMPT = """
You turn a question about someone's M-Pesa money into a JSON query plan. Reply with JSON only, no text.
Fields (all optional except metric):
 "metric": one of SUM, COUNT, AVG, MAX, MIN, RANK, NET, CUSTOMERS, ADVICE, STATUS, REVIEW, PRICES, COMPARE
 "direction": "in" (money received) or "out" (money spent). Default "out".
 "period": one of today, yesterday, this_week, last_week, next_week, this_month, last_month, next_month, last_30_days, last_3_months, this_year, all
   or an object {"from":"YYYY-MM-DD","to":"YYYY-MM-DD","label":"words"}
 "categories": list from: ${categoryWords.joinToString(", ") { it.second }}
 "bucket": "Business" or "Personal"
 "name": a shop or person name fragment, if the question names one
 "min_amount", "max_amount": numbers in KES
 "group_by": one of CATEGORY, COUNTERPARTY, DAY, WEEKDAY, MONTH (used with RANK)
 "per_unit": "day" or "week" (for averages like "per day")
 "horizon": "today", "week" or "month" (for ADVICE: budget advice)
Examples: "how much can I spend tomorrow" -> {"metric":"ADVICE","period":"today","horizon":"today"}
          "food spending in the last 3 months" -> {"metric":"SUM","categories":["Personal - Food & Household"],"period":"last_3_months"}
""".trim()

    fun fromJson(json: String, ref: LocalDate): QueryPlan? = try {
        val o = MiniJson.parse(json) as? Map<*, *> ?: return null
        val metric = Metric.valueOf((o["metric"] as String).uppercase())
        val monday = Advisor.weekStart(ref)
        val periodSpec = o["period"]
        val (range, label) = when (periodSpec) {
            "today" -> DateRange(ref, ref) to "today"; "yesterday" -> DateRange(ref.minusDays(1), ref.minusDays(1)) to "yesterday"
            "this_week" -> DateRange(monday, ref) to "this week"; "last_week" -> DateRange(monday.minusWeeks(1), monday.minusDays(1)) to "last week"
            "next_week" -> DateRange(monday.plusWeeks(1), monday.plusDays(13)) to "next week"
            "this_month" -> DateRange(ref.withDayOfMonth(1), ref) to "this month"
            "last_month" -> ref.withDayOfMonth(1).minusMonths(1).let { DateRange(it, it.withDayOfMonth(it.lengthOfMonth())) to "last month" }
            "next_month" -> ref.withDayOfMonth(1).plusMonths(1).let { DateRange(it, it.withDayOfMonth(it.lengthOfMonth())) to "next month" }
            "last_30_days" -> DateRange(ref.minusDays(29), ref) to "in the last 30 days"; "last_3_months" -> DateRange(ref.minusMonths(3).plusDays(1), ref) to "in the last 3 months"
            "this_year" -> DateRange(ref.withDayOfYear(1), ref) to "this year"; "all" -> DateRange(null, null) to "overall"
            is Map<*, *> -> DateRange((periodSpec["from"] as? String)?.let { LocalDate.parse(it) }, (periodSpec["to"] as? String)?.let { LocalDate.parse(it) }) to ((periodSpec["label"] as? String) ?: "in that period")
            else -> DateRange(ref.withDayOfMonth(1), ref) to "this month"
        }
        val future = periodSpec.toString().startsWith("next") || periodSpec == "tomorrow"
        QueryPlan(metric, if ((o["direction"] as? String)?.lowercase() == "in") Dir.IN else Dir.OUT, o["direction"] != null, range, label, periodSpec != null,
            (o["horizon"] as? String)?.let { Horizon.valueOf(it.uppercase()) }, future,
            (o["categories"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet(), o["bucket"] as? String, o["name"] as? String,
            (o["min_amount"] as? Double), (o["max_amount"] as? Double),
            (o["group_by"] as? String)?.let { GroupBy.valueOf(it.uppercase()) } ?: GroupBy.NONE, o["per_unit"] as? String)
    } catch (_: Exception) { null }
}

/** A tiny JSON reader (objects, arrays, strings, numbers, true/false/null), so the core needs no libraries. */
object MiniJson {
    fun parse(s: String): Any? { val p = P(s); val v = p.value(); p.ws(); require(p.i == s.length) { "trailing text" }; return v }
    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? { ws(); return when (s[i]) {
            '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
                while (true) { ws(); val k = str(); ws(); require(s[i++] == ':'); m[k] = value(); ws(); if (s[i] == ',') i++ else { require(s[i++] == '}'); return m } } }
            '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
                while (true) { l.add(value()); ws(); if (s[i] == ',') i++ else { require(s[i++] == ']'); return l } } }
            '"' -> str()
            't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
            else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; s.substring(st, i).toDouble() } } }
        fun str(): String { require(s[i++] == '"'); val sb = StringBuilder()
            while (s[i] != '"') { if (s[i] == '\\') { i++; when (s[i]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }; else -> sb.append(s[i]) } } else sb.append(s[i]); i++ }
            i++; return sb.toString() }
    }
}

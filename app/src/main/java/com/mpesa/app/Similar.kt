package com.mpesa.app

/** Finds shops that probably belong with one the user just answered (QUICK MART KILELESHWA and TABS QUICK MART). Suggestions only: the user confirms. */
object Similar {
    private val generic = setOf("LTD", "LIMITED", "AND", "THE", "SHOP", "STORE", "STORES", "KENYA", "NAIROBI", "SERVICES", "ENTERPRISES",
        "ENTERPRISE", "COMPANY", "MPESA", "PAYBILL", "PAY", "BILL", "ACC", "AGENT", "CENTRE", "CENTER", "TRADING", "SUPPLIES")

    fun tokens(name: String): Set<String> =
        name.uppercase().split(Regex("[^A-Z0-9]+")).filter { it.length >= 3 && it !in generic && !it.all { c -> c.isDigit() } }.toSet()

    /** share of the smaller name's words found in the other, 0 when nothing distinctive (4+ letters) is shared */
    fun score(a: String, b: String): Double {
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val shared = ta.intersect(tb)
        if (shared.none { it.length >= 4 }) return 0.0
        return shared.size.toDouble() / minOf(ta.size, tb.size)
    }

    /** Unanswered questions that look like the one just answered. People are never matched. */
    fun pending(answered: ReviewItem, queue: List<ReviewItem>, minScore: Double = 0.5): List<ReviewItem> =
        if (answered.isPerson) emptyList()
        else queue.filter { !it.isPerson && it.counterparty != answered.counterparty && score(answered.displayName, it.displayName) >= minScore }
}

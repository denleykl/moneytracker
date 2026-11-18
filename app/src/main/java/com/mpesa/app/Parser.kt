package com.mpesa.app

/**
 * Port of parse(), ckey() and add_groups() from build_labels.py.
 * Turns the free-text `Details` column into (tx_group, name, merchant_key, group_id).
 */
class ParsedDetails(val txGroup: String, val name: String)
class ParsedRow(val txGroup: String, val name: String, val merchantKey: String) {
    val groupId: String get() = "$txGroup|$merchantKey"
}

object MpesaParser {
    const val MIN_PERSON_TXNS = 3
    const val OTHER_PEOPLE = "__OTHERPEOPLE__"
    private val I = RegexOption.IGNORE_CASE

    private val fulizaTo = Regex("Fuliza\\s*M-?Pesa\\s*to", I)
    private val kind = Regex(
        "^(Customer Send Money to MicroSME Business|Merchant Payment|Pay Bill(?: Online)?|Customer Payment to Small Business|" +
            "Customer Transfer|Funds received from|Business Payment from|Customer Withdrawal|Withdraw(?:al)?|" +
            "Airtime Purchase|Deposit of Funds|Reversal|Promotion Payment|Salary Payment)", I)
    private val groupOf = mapOf(
        "customer send money to microsme business" to "merchant", "merchant payment" to "merchant",
        "pay bill" to "paybill", "pay bill online" to "paybill", "customer payment to small business" to "merchant",
        "customer transfer" to "person_out", "funds received from" to "person_in", "business payment from" to "person_in")
    private val fixedKinds = setOf("airtime_purchase", "withdrawal", "customer_withdrawal", "deposit_of_funds",
        "reversal", "promotion_payment", "salary_payment")
    private val nameGroups = setOf("merchant", "paybill", "person_out", "person_in")

    private val digitsStars = Regex("[\\d*]+")
    private val withFuliza = Regex("\\bwith\\s+fuliza\\b|\\bfuliza\\b", I)
    private val spaces = Regex("\\s+")
    private val leadFiller = Regex("^[\\s\\-]*(?:with\\s+|to\\s*|from\\s*)+", I)
    private val leadId = Regex("^[\\s\\-]*[\\d*]+\\s*-?\\s*")
    private val accRef = Regex("\\bAcc\\.?.*$", I)
    private val longDigits = Regex("[\\d*]{5,}")
    private val prefixLeft = Regex("^(?:FULIZA M-?PESA ?)?(?:ONLINE ?)?(?:TO ?)\\d+ ?- ?")
    private val trailing = Regex("[\\d+*]+$")
    private val notKey = Regex("[^A-Z0-9]")
    private val charge = Regex("charge", I)

    /** Spelling-proof key: 'KPLC PREPAID' and 'KPLCPREPAID' become the same merchant. */
    fun ckey(name: String): String = notKey.replace(name.uppercase(), "")

    fun parse(details: String): ParsedDetails {
        val s0 = fulizaTo.replace(details, "to")
        val low = s0.lowercase()
        if (low.startsWith("overdraft of credit")) return ParsedDetails("fuliza_advance", "FULIZA ADVANCE (loan funding a payment)")
        if (low.startsWith("od loan repayment")) return ParsedDetails("fuliza_repayment", "FULIZA REPAYMENT")

        val m = kind.find(s0)
        if (m == null) {
            var name = digitsStars.replace(s0, " ")
            name = withFuliza.replace(name, " ")
            name = spaces.replace(name, " ").trim { it == ' ' || it == '-' }.uppercase()
            return ParsedDetails("other", name.ifEmpty { "(blank)" })
        }
        val kindText = m.groupValues[1].lowercase()
        val group = groupOf[kindText] ?: kindText.replace(" ", "_")
        if (group in fixedKinds) {
            return ParsedDetails(group, if (charge.containsMatchIn(s0)) "CHARGE" else kindText.uppercase())
        }
        val rest = leadFiller.replaceFirst(s0.substring(m.range.last + 1), "")
        var name = if (group in nameGroups) accRef.replace(leadId.replaceFirst(rest, ""), "") else longDigits.replace(rest, "")
        name = name.uppercase()
        repeat(3) { name = prefixLeft.replaceFirst(name, "").trim() }
        name = trailing.replace(name, "")
        name = spaces.replace(name, " ").trim { it == ' ' || it == '-' || it == '.' }
        return ParsedDetails(group, name.ifEmpty { "(blank)" })
    }

    /** People paid/received fewer than MIN_PERSON_TXNS times are lumped into one group (privacy + less to review). */
    fun addGroups(details: List<String>): List<ParsedRow> {
        val p = details.map { parse(it) }
        val keys = p.map { ckey(it.name) }.toMutableList()
        for (g in listOf("person_out", "person_in")) {
            val idx = p.indices.filter { p[it].txGroup == g }
            val counts = idx.groupingBy { keys[it] }.eachCount()
            for (i in idx) if (counts.getValue(keys[i]) < MIN_PERSON_TXNS) keys[i] = OTHER_PEOPLE
        }
        return p.indices.map { ParsedRow(p[it].txGroup, p[it].name, keys[it]) }
    }
}

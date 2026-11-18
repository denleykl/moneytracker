package com.mpesa.app

enum class Confidence { HIGH, MEDIUM }
class RuleHit(val category: String, val confidence: Confidence, val why: String)

/** Port of suggest() from build_labels.py: keyword rules on the merchant name. */
object Rules {
    private class Rule(val category: String, val conf: Confidence, val keys: List<String>)
    private val H = Confidence.HIGH
    private val M = Confidence.MEDIUM

    private val substr = listOf(
        Rule("Fees & Charges", H, listOf("CHARGE", "WITHDRAWALCHARGE")),
        Rule("Savings, Loans & Fuliza", H, listOf("FULIZA", "OVERDRAFT", "MSHWARI", "KCBMPESA", "LOCKSAVINGS", "SAVINGS", "LOAN")),
        Rule("Savings, Loans & Fuliza", M, listOf("EQUITYPAYBILL", "COOPERATIVEBANK", "MONEYTRANSFER", "BANK", "CHAMA", "SACCOCONTRIB")),
        Rule("Personal - Bills & Utilities", H, listOf("KPLC", "AIRTIME", "SAFARICOMOFFERS", "NAIROBIWATER", "DSTV", "GOTV", "ZUKU", "STARTIMES", "KONNEKT", "WIFI")),
        Rule("Personal - Bills & Utilities", M, listOf("RENT", "WATER", "INTERNET", "SCHOOLFEE", "NHIF")),
        Rule("Personal - Lifestyle & Entertainment", H, listOf("BETIKA", "SPORTPESA", "ODIBETS", "MOZZART", "BETWAY", "CASINO", "WINES", "SPIRITS", "LIQUOR")),
        Rule("Personal - Food & Household", H, listOf("SUPERMARKET", "NAIVAS", "QUICKMART", "CARREFOUR", "BUTCHERY", "RESTAURANT", "HOTEL", "CAFE", "CANTEEN", "TEAROOM")),
        Rule("Personal - Food & Household", M, listOf("FOOD", "KITCHEN", "BAKERY", "GROCER", "FISH", "PORK", "MEAT", "MILK", "CHEMIST", "PHARMACY", "EATERY", "JUICE", "COFFEE", "PIZZA", "CHICKEN", "FRY", "EAT")),
        Rule("Business - Stock", M, listOf("WHOLESALE", "DISTRIBUTOR", "SUPPLIER", "SUPPLIES", "TRADERS", "WAKULIMA", "MARIKITI", "GIKOMBA", "PRODUCE", "CEREAL", "AGROVET", "FEEDS", "MILLERS", "HARDWARE")),
        Rule("Business - Operating costs", M, listOf("FUEL", "PETROL", "SHELL", "RUBIS", "TOTALENERGIES", "MATATU", "BODABODA", "LOGISTICS", "LICENCE", "LICENSE", "PARKING", "STATIONERS")),
    )
    private val words = listOf(
        Rule("Personal - Lifestyle & Entertainment", M, listOf("BAR", "PUB", "CLUB", "LOUNGE", "BET")),
        Rule("Personal - Food & Household", M, listOf("SHOP", "DUKA", "MART", "STORES", "TEA", "MAMA", "CANTEEN", "KIOSK")),
    )
    private val wordRegex = words.map { r -> r to r.keys.map { it to Regex("\\b$it\\b") } }

    fun suggest(txGroup: String, name: String): RuleHit? {
        val key = MpesaParser.ckey(name)
        when (txGroup) {
            "fuliza_advance", "fuliza_repayment" -> return RuleHit("Savings, Loans & Fuliza", H, "Fuliza")
            "person_out", "person_in" -> return RuleHit("Person-to-Person", M, "paid/received a person")
            "business_payment_from" -> return RuleHit("Business - Sales income", M, "business payment received")
            "withdrawal", "customer_withdrawal" -> if ("CHARGE" !in key) return RuleHit("Cash Withdrawal", H, "cash withdrawal")
        }
        for (r in substr) for (k in r.keys) if (k in key) return RuleHit(r.category, r.conf, "name contains '$k'")
        val upper = name.uppercase()
        for ((r, rx) in wordRegex) for ((w, re) in rx) if (re.containsMatchIn(upper)) return RuleHit(r.category, r.conf, "name has word '$w'")
        if ("INTERNATIONAL" in key || "SENDWAVE" in key) return RuleHit("Person-to-Person", M, "international transfer")
        return null
    }
}

/** Merchants you have already labelled by hand (export_known.py turns merchant_labels.csv into known_merchants.csv). */
class KnownMerchants(private val byGroup: Map<String, String>) {
    val size: Int get() = byGroup.size
    fun lookup(tx: Transaction): String? = byGroup[tx.groupId]

    companion object {
        /** CSV with the columns group_id,category */
        fun load(input: java.io.InputStream): KnownMerchants {
            val rows = CsvReader.parse(input.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF"))
            if (rows.isEmpty()) return KnownMerchants(emptyMap())
            val h = rows[0].map { it.trim() }
            val g = h.indexOf("group_id"); val c = h.indexOf("category")
            require(g >= 0 && c >= 0) { "known_merchants.csv needs the columns group_id,category" }
            return KnownMerchants(rows.drop(1).filter { it.size > maxOf(g, c) && it[c].isNotBlank() }.associate { it[g] to it[c].trim() })
        }
    }
}

package com.mpesa.app

import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.DayOfWeek
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln1p
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One cleaned statement row, i.e. what the notebook has after steps 1 to 7.
 * txGroup / name / merchantKey come from the statement parser (build_labels.add_groups in Python).
 */
data class Transaction(
    val details: String,
    val txGroup: String,
    val name: String,
    val merchantKey: String,
    val paidIn: Double,
    val withdrawn: Double,
    val time: LocalDateTime?,
    val receipt: String = "",
) {
    val amount: Double get() = paidIn + withdrawn
    /** Same id as build_labels.py's group_id, e.g. "paybill|KPLCPREPAID". */
    val groupId: String get() = "$txGroup|$merchantKey"
    val isInflow: Boolean get() = paidIn > 0
}

class TxFeatures(val counterparty: String, val text: String, val numeric: Map<String, Double>)

object TextCleaner {
    private val phone = Regex("(?:\\+?254|0)7[\\d*]{7,8}")
    private val longNum = Regex("\\d{5,}")
    private val nonAlpha = Regex("[^a-z\\s]")
    private val spaces = Regex("\\s+")
    private val generic = mapOf("person_out" to "customer transfer to person", "person_in" to "funds received from person")

    fun cleanDetails(s: String): String {
        var t = s.lowercase()
        t = phone.replace(t, " phone ")
        t = longNum.replace(t, " num ")
        t = nonAlpha.replace(t, " ")
        return spaces.replace(t, " ").trim()
    }

    /** People's names never reach the model: transfers get a fixed sentence. */
    fun modelText(tx: Transaction): String = generic[tx.txGroup] ?: cleanDetails(tx.details)
}

object Counterparty {
    private val after = Regex("^.*?(?:\\bto\\b|\\bfrom\\b|m-?pesato)\\s*-?\\s*", RegexOption.IGNORE_CASE)
    private val spaces = Regex("\\s+")
    private val keep = Regex("[^A-Z0-9*]")

    fun displayName(tx: Transaction): String = when (tx.txGroup) {
        "person_out", "person_in" -> spaces.replace(after.replaceFirst(tx.details, "").uppercase(), " ").trim()
        "merchant", "paybill" -> tx.name
        else -> tx.txGroup
    }

    /** Pseudonymous ID: the first 12 hex characters of SHA-256. Not reversible, but not full anonymisation either. */
    fun id(tx: Transaction): String {
        val key = when (tx.txGroup) {
            "person_out", "person_in" -> "P:" + keep.replace(displayName(tx), "")
            "merchant", "paybill" -> "M:" + tx.merchantKey
            else -> "T:" + tx.txGroup
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.substring(0, 12)
    }
}

/** Port of notebook section 2. Works on a WHOLE statement, because the behaviour features are per-counterparty aggregates. */
object FeatureBuilder {
    private val biz = Regex("FOOD|CAFE|HOTEL|SUPPL|TRANSPORT|CABS?\\b|TAXI|SHOP|STORE|ENTERPRISE|\\bENT\\b|LTD|KITCHEN|BUTCHER|MAMA|WHOLESALE")

    fun build(txs: List<Transaction>): List<TxFeatures> {
        val cp = txs.map { Counterparty.id(it) }
        val byCp = cp.indices.groupBy { cp[it] }

        // distinct inflow counterparties per calendar day
        val sendersPerDay = HashMap<LocalDate, MutableSet<String>>()
        for (i in txs.indices) { val d = txs[i].time?.toLocalDate(); if (d != null && txs[i].isInflow) sendersPerDay.getOrPut(d) { HashSet() }.add(cp[i]) }

        // days since the previous transaction with the same counterparty (365 if none / unknown time)
        val gapDays = DoubleArray(txs.size) { 365.0 }
        for ((_, idx) in byCp) {
            val timed = idx.filter { txs[it].time != null }.sortedBy { txs[it].time }
            for (j in 1 until timed.size) {
                val secs = java.time.Duration.between(txs[timed[j - 1]].time, txs[timed[j]].time).seconds
                gapDays[timed[j]] = minOf(secs / 86400.0, 365.0)
            }
        }

        return txs.indices.map { i ->
            val tx = txs[i]
            val grp = byCp.getValue(cp[i])
            val amounts = grp.map { txs[it].amount }
            val n = amounts.size
            val mean = amounts.average()
            val std = if (n > 1) sqrt(amounts.sumOf { (it - mean) * (it - mean) } / (n - 1)) else Double.NaN
            val cv = (std / mean).let { if (it.isNaN() || it.isInfinite()) 0.0 else it }.coerceIn(0.0, 5.0)
            val hour = tx.time?.hour?.toDouble() ?: 12.0
            val activeDays = grp.mapNotNull { txs[it].time?.toLocalDate() }.toSet().size

            val num = linkedMapOf(
                "log_amount" to ln1p(tx.amount),
                "is_inflow" to if (tx.isInflow) 1.0 else 0.0,
                "hour_sin" to sin(2 * PI * hour / 24),
                "hour_cos" to cos(2 * PI * hour / 24),
                "is_weekend" to if (tx.time?.dayOfWeek.let { it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY }) 1.0 else 0.0,
                "cp_count" to ln1p(n.toDouble()),
                "cp_active_days" to ln1p(activeDays.toDouble()),
                "cp_mean_amt" to ln1p(mean),
                "cp_amt_cv" to cv,
                "cp_inflow_share" to grp.count { txs[it].isInflow }.toDouble() / n,
                "cp_gap_days" to ln1p(gapDays[i]),
                "round_amount" to if (tx.amount % 10.0 == 0.0) 1.0 else 0.0,
                "senders_today" to ln1p((tx.time?.toLocalDate()?.let { sendersPerDay[it]?.size } ?: 0).toDouble()),
                "biz_name_flag" to if (biz.containsMatchIn(Counterparty.displayName(tx).uppercase())) 1.0 else 0.0,
            )
            TxFeatures(cp[i], TextCleaner.modelText(tx), num)
        }
    }
}

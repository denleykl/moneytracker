package com.mpesa.app

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/** A price range at which this trader usually sells, e.g. KES 100 - 300. */
class PriceBand(val low: Double, val high: Double, val count: Int = 0, val senders: Int = 0, val manual: Boolean = false) {
    fun contains(x: Double) = x >= low && x <= high
    val label: String get() = "KES %,.0f - %,.0f".format(low, high)
}

class BandMatch(val band: PriceBand, val confidence: Double, val why: String)
class SupplierHint(val confidence: Double, val why: String)

/**
 * What this trader's business looks like, learned from their own money-in history (or typed in by them):
 *  - sale price bands: ranges of amounts that many different people pay again and again
 *  - which senders are customers (repeat, one-way payers) and which are family/friends (money goes both ways)
 *  - suppliers: someone you pay a steady amount to at regular intervals
 */
class BusinessProfile(
    val bands: List<PriceBand>,
    val lookbackDays: Int,
    val ref: LocalDate,
    private val inBandCount: Map<String, Int>,     // counterparty -> number of payments inside a band (whole history)
    private val inflowShare: Map<String, Double>,  // counterparty -> share of their payments that were money IN
    private val supplierStats: Map<String, SupplierHint>,
) {
    val isEmpty: Boolean get() = bands.isEmpty()

    /** Is this incoming payment probably a sale? */
    fun match(tx: Transaction, counterparty: String): BandMatch? {
        if (!tx.isInflow || tx.txGroup != "person_in") return null
        val band = bands.firstOrNull { it.contains(tx.amount) } ?: return null
        if ((inflowShare[counterparty] ?: 1.0) < 0.8) return null          // money also goes TO them: more like family or a friend
        val repeats = inBandCount[counterparty] ?: 0
        val where = "KES %,.0f is inside your usual sale prices (%s)".format(tx.amount, band.label)
        return when {
            repeats >= 2 -> BandMatch(band, 0.85, "$where, and this person has paid you in that range $repeats times.")
            band.manual -> BandMatch(band, 0.75, "$where, a range you told me you sell at.")
            band.count >= 20 && band.senders >= 5 -> BandMatch(band, 0.70,
                "$where: ${band.count} payments from ${band.senders} different people in the last $lookbackDays days.")
            else -> null
        }
    }

    /** Is this outgoing payment probably buying stock? A suggestion only. */
    fun supplierHint(tx: Transaction, counterparty: String): SupplierHint? =
        if (tx.isInflow || (tx.txGroup != "person_out" && tx.txGroup != "merchant")) null else supplierStats[counterparty]
}

object Business {
    private const val SPLIT_RATIO = 1.6      // a jump of more than 1.6x between neighbouring amounts starts a new band
    private const val MAX_WIDTH = 8.0        // a band may span at most 8x (e.g. 100 to 800)
    private fun nice(x: Double, up: Boolean): Double {
        val step = if (x < 1000) 10.0 else if (x < 10000) 50.0 else 500.0
        return if (up) ceil(x / step) * step else floor(x / step) * step
    }
    private fun pct(sorted: List<Double>, p: Double) = sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]

    /** Splits sorted amounts at big jumps, then at the biggest jump inside any range that is too wide. */
    private fun clusters(sorted: List<Double>): List<List<Double>> {
        val out = ArrayList<List<Double>>(); var cur = ArrayList<Double>()
        for (x in sorted) { if (cur.isNotEmpty() && x / cur.last() > SPLIT_RATIO) { out.add(cur); cur = ArrayList() }; cur.add(x) }
        if (cur.isNotEmpty()) out.add(cur)
        fun widen(c: List<Double>): List<List<Double>> {
            if (c.last() / c.first() <= MAX_WIDTH || c.size < 2) return listOf(c)
            val cut = (1 until c.size).maxByOrNull { c[it] / c[it - 1] }!!
            return widen(c.subList(0, cut)) + widen(c.subList(cut, c.size))
        }
        return out.flatMap { widen(it) }
    }

    /** Price bands from the incoming payments of the last `lookbackDays` days. */
    fun detectBands(txs: List<Transaction>, ref: LocalDate, lookbackDays: Int, minCount: Int = 8, minSenders: Int = 3): List<PriceBand> {
        val from = ref.minusDays((lookbackDays - 1).toLong())
        val inflows = txs.filter { it.isInflow && it.txGroup == "person_in" && it.time?.toLocalDate()?.let { d -> !d.isBefore(from) && !d.isAfter(ref) } == true }
        if (inflows.size < minCount) return emptyList()
        val sorted = inflows.map { it.amount }.sorted()
        return clusters(sorted).mapNotNull { c ->
            val members = inflows.filter { it.amount >= c.first() && it.amount <= c.last() }
            val senders = members.map { Counterparty.id(it) }.toSet().size
            if (c.size < minCount || senders < minSenders) null
            else PriceBand(nice(pct(c, 0.05), false), nice(pct(c, 0.95), true), c.size, senders)
        }
    }

    /**
     * Builds the profile. `manual` bands (typed in by the user) are used as they are; with `auto` the bands are also learned from the
     * last `lookbackDays` days, and a learned band that overlaps a typed one is dropped (the user wins).
     */
    fun profile(txs: List<Transaction>, lookbackDays: Int = 90, manual: List<PriceBand> = emptyList(), auto: Boolean = true,
                ref: LocalDate = txs.mapNotNull { it.time?.toLocalDate() }.maxOrNull() ?: LocalDate.now()): BusinessProfile {
        val learned = if (auto) detectBands(txs, ref, lookbackDays).filter { l -> manual.none { m -> l.low <= m.high && m.low <= l.high } } else emptyList()
        val bands = (manual.map { PriceBand(it.low, it.high, 0, 0, true) } + learned).sortedBy { it.low }

        val cp = txs.map { Counterparty.id(it) }
        val byCp = txs.indices.groupBy { cp[it] }
        val inBand = HashMap<String, Int>(); val share = HashMap<String, Double>(); val supplier = HashMap<String, SupplierHint>()
        for ((id, idx) in byCp) {
            val rows = idx.map { txs[it] }
            share[id] = rows.count { it.isInflow }.toDouble() / rows.size
            inBand[id] = rows.count { it.isInflow && it.txGroup == "person_in" && bands.any { b -> b.contains(it.amount) } }
            val outs = rows.filter { !it.isInflow && (it.txGroup == "person_out" || it.txGroup == "merchant") && it.time != null }
            val days = outs.mapNotNull { it.time?.toLocalDate() }.distinct().sorted()
            if (outs.size >= 3 && days.size >= 3) {
                val amts = outs.map { it.amount }; val mean = amts.average()
                val sd = sqrt(amts.sumOf { (it - mean) * (it - mean) } / (amts.size - 1))
                val gaps = days.zipWithNext { a, b -> java.time.temporal.ChronoUnit.DAYS.between(a, b).toDouble() }
                if (mean >= 500 && sd / mean <= 0.35 && gaps.average() >= 2.0)
                    supplier[id] = SupplierHint(0.6, "You paid this ${if (outs[0].txGroup == "person_out") "person" else "shop"} ${outs.size} times, about KES %,.0f each, roughly every %.0f days: this looks like restocking.".format(mean, gaps.average()))
            }
        }
        return BusinessProfile(bands, lookbackDays, ref, inBand, share, supplier)
    }

    private const val NUM = "(\\d{1,3}(?:,\\d{3})+|\\d+)(?!\\d)"      // 1,500 or 1500 (the comma may be a thousands separator)

    /** "100-300, 1,500 - 3,000" -> bands (for saving the user's own prices) */
    fun parseBands(s: String): List<PriceBand> = Regex("$NUM\\s*(?:-|to)\\s*$NUM").findAll(s).mapNotNull { m ->
        val a = m.groupValues[1].replace(",", "").toDoubleOrNull(); val b = m.groupValues[2].replace(",", "").toDoubleOrNull()
        if (a == null || b == null || a <= 0 || b < a) null else PriceBand(a, b, 0, 0, true)
    }.toList()
    fun formatBands(b: List<PriceBand>): String = b.joinToString(",") { "%.0f-%.0f".format(it.low, it.high) }
}

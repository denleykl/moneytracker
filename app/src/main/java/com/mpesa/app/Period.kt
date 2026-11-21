package com.mpesa.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class Preset(val label: String) { THIS_WEEK("This week"), THIS_MONTH("This month"), LAST_30("30 days"), LAST_3M("3 months"), ALL("All") }

class DateRange(val from: LocalDate?, val to: LocalDate?) {
    val isAll: Boolean get() = from == null && to == null
    fun contains(d: LocalDate) = (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to))
}

object Periods {
    /** The "today" of the data: the date of the newest transaction (a 2023 statement is not judged against today's date). */
    fun reference(items: List<Categorised>, fallback: LocalDate = LocalDate.now()): LocalDate =
        items.mapNotNull { it.tx.time?.toLocalDate() }.maxOrNull() ?: fallback

    fun resolve(p: Preset, ref: LocalDate): DateRange = when (p) {
        Preset.THIS_WEEK -> DateRange(ref.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), ref)
        Preset.THIS_MONTH -> DateRange(ref.withDayOfMonth(1), ref)
        Preset.LAST_30 -> DateRange(ref.minusDays(29), ref)
        Preset.LAST_3M -> DateRange(ref.minusMonths(3).plusDays(1), ref)
        Preset.ALL -> DateRange(null, null)
    }

    /** Rows inside the range. "All" keeps rows with an unknown time too. */
    fun filter(items: List<Categorised>, r: DateRange): List<Categorised> =
        if (r.isAll) items else items.filter { val d = it.tx.time?.toLocalDate(); d != null && r.contains(d) }

    fun filterTx(txs: List<Transaction>, r: DateRange): List<Transaction> =
        if (r.isAll) txs else txs.filter { val d = it.time?.toLocalDate(); d != null && r.contains(d) }
}

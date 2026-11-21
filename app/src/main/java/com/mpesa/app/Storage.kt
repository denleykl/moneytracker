package com.mpesa.app

/**
 * Saved statement rows. The RAW rows are stored (not the parsed transactions) and everything is re-processed after each import,
 * so people-grouping and features always use the whole saved history, however many imports built it.
 */
object RawCodec {
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    fun encode(rows: List<RawRow>): String =
        rows.joinToString("\n") { listOf(it.receipt, it.time, it.details, it.status, it.paidIn, it.withdrawn).joinToString("\t") { f -> clean(f) } }

    fun decode(text: String): List<RawRow> = text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
        val f = line.split("\t")
        if (f.size < 6) null else RawRow(f[0], f[1], f[2], f[3], f[4], f[5])
    }
}

object RawMerge {
    private val receipt = Regex("[A-Z0-9]{10}")

    /** Saved rows first, then new ones that are not already saved (same receipt and details). Non-transaction rows are dropped. */
    fun merge(existing: List<RawRow>, incoming: List<RawRow>): List<RawRow> {
        val seen = HashSet<String>(); val out = ArrayList<RawRow>()
        for (r in existing + incoming) {
            if (!receipt.matches(r.receipt)) continue
            if (seen.add(r.receipt + "|" + r.details)) out.add(r)
        }
        return out
    }
}

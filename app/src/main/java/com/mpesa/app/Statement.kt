package com.mpesa.app

import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** One row of the statement exactly as read from the file (all text). */
class RawRow(val receipt: String, val time: String, val details: String, val status: String, val paidIn: String, val withdrawn: String)

/** Minimal RFC-4180 CSV reader: quoted fields, escaped quotes, commas and newlines inside quotes, CRLF. */
object CsvReader {
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val f = StringBuilder()
        var inQuotes = false
        var i = 0
        fun endField() { row.add(f.toString()); f.setLength(0) }
        fun endRow() { endField(); if (!(row.size == 1 && row[0].isEmpty())) rows.add(row); row = ArrayList() }
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { f.append('"'); i++ } else inQuotes = false }
                else f.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                ',' -> endField()
                '\r' -> { if (i + 1 < text.length && text[i + 1] == '\n') i++; endRow() }
                '\n' -> endRow()
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}

object StatementCsv {
    private val required = listOf("Receipt No.", "Completion Time", "Details", "Transaction Status", "Paid In", "Withdrawn")

    /** Reads a CSV statement. The layout is detected from the header: the notebook's statement columns, or a PesaSense export. */
    fun read(input: InputStream): List<RawRow> {
        val rows = CsvReader.parse(input.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        require(rows.isNotEmpty()) { "Empty file" }
        val header = rows[0].map { it.trim() }
        if ("Recipient/Merchant" in header && "Type" in header) return PesaSenseCsv.read(header, rows.drop(1))
        val col = required.associateWith { header.indexOf(it) }
        val missing = col.filterValues { it < 0 }.keys
        require(missing.isEmpty()) { "Unrecognised CSV layout. Missing columns: $missing. Found: $header" }
        fun cell(r: List<String>, name: String) = r.getOrNull(col.getValue(name)) ?: ""
        return rows.drop(1).map { r ->
            RawRow(cell(r, "Receipt No."), cell(r, "Completion Time"), cell(r, "Details"),
                cell(r, "Transaction Status"), cell(r, "Paid In"), cell(r, "Withdrawn"))
        }
    }
}

/**
 * Adapter for CSV exports of the PesaSense app (Date, Time, Code, Type, Amount (KES), Recipient/Merchant, Phone/Account, ...).
 * That layout has no free-text Details, so one is rebuilt in the wording of a Safaricom statement and fed to the normal parser.
 * Limits: the Fees column is ignored (a real statement lists fees as separate rows); only four transaction types exist in the export.
 */
object PesaSenseCsv {
    fun read(header: List<String>, rows: List<List<String>>): List<RawRow> {
        fun idx(vararg names: String) = names.map { header.indexOf(it) }.firstOrNull { it >= 0 } ?: -1
        val cDate = idx("Date"); val cTime = idx("Time"); val cCode = idx("Code"); val cType = idx("Type")
        val cAmt = idx("Amount (KES)", "Amount"); val cWho = idx("Recipient/Merchant"); val cPhone = idx("Phone/Account")
        require(listOf(cDate, cTime, cCode, cType, cAmt, cWho).all { it >= 0 }) { "PesaSense export is missing a required column: $header" }
        fun cell(r: List<String>, i: Int) = if (i >= 0) (r.getOrNull(i) ?: "").trim() else ""

        return rows.mapNotNull { r ->
            val type = cell(r, cType).lowercase()
            val who = cell(r, cWho); val phone = cell(r, cPhone)
            val details = when (type) {
                "till payment"    -> "Merchant Payment to 0000000 - $who"
                "paybill payment" -> "Pay Bill to 0000000 - $who"
                "money sent"      -> "Customer Transfer to - ${phone.filter { it.isDigit() }} $who"
                "money received"  -> "Funds received from - $who"
                else -> return@mapNotNull null                         // unknown type: skip rather than guess
            }
            val amount = cell(r, cAmt)
            val inflow = type == "money received"
            RawRow(cell(r, cCode), toIso(cell(r, cDate), cell(r, cTime)), details, "Completed",
                paidIn = if (inflow) amount else "", withdrawn = if (inflow) "" else amount)
        }
    }

    /** "17/11/2026" + "21:50" -> "2026-11-17 21:50:00" (empty if it cannot be read) */
    private fun toIso(date: String, time: String): String {
        val d = date.split("/"); val t = time.split(":")
        if (d.size != 3 || t.size < 2) return ""
        return "%s-%s-%s %s:%s:%s".format(d[2], d[1].padStart(2, '0'), d[0].padStart(2, '0'),
            t[0].padStart(2, '0'), t[1].padStart(2, '0'), t.getOrNull(2)?.padStart(2, '0') ?: "00")
    }
}

/** Port of notebook steps 1 to 7: drop non-transactions, keep completed, de-duplicate, numbers, parse Details, drop Fuliza advances. */
object StatementProcessor {
    private val receiptRe = Regex("[A-Z0-9]{10}")
    private val formats = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm", "dd-MM-yyyy HH:mm:ss",
        "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm").map { DateTimeFormatter.ofPattern(it) }

    fun parseTime(s: String): LocalDateTime? {
        val t = s.trim().removeSuffix(".0")
        if (t.isEmpty()) return null
        for (f in formats) try { return LocalDateTime.parse(t, f) } catch (_: Exception) {}
        return null
    }

    /** Collapse any run of whitespace to one space and trim (no regex flags, so it behaves the same on Android and the JVM). */
    private fun collapseSpaces(s: String): String {
        val sb = StringBuilder(s.length)
        var prevSpace = false
        for (c in s) {
            if (c.isWhitespace()) { if (!prevSpace) sb.append(' '); prevSpace = true } else { sb.append(c); prevSpace = false }
        }
        return sb.toString().trim()
    }

    private fun money(s: String): Double {
        val v = s.replace(",", "").trim().toDoubleOrNull() ?: return 0.0
        return if (v.isNaN() || v.isInfinite()) 0.0 else v
    }

    fun process(raw: List<RawRow>): List<Transaction> {
        val seen = HashSet<List<String>>()
        val kept = raw.filter { receiptRe.matches(it.receipt) }                         // drops repeated headers / footers
            .filter { it.status.trim().lowercase() == "completed" }
            .filter { seen.add(listOf(it.receipt, it.details, it.paidIn, it.withdrawn)) }  // exact duplicates
        val details = kept.map { collapseSpaces(it.details) }
        val parsed = MpesaParser.addGroups(details)
        val out = ArrayList<Transaction>()
        for (i in kept.indices) {
            if (parsed[i].txGroup == "fuliza_advance") continue                          // the loan half of a Fuliza payment
            out.add(Transaction(details[i], parsed[i].txGroup, parsed[i].name, parsed[i].merchantKey,
                money(kept[i].paidIn), abs(money(kept[i].withdrawn)), parseTime(kept[i].time), kept[i].receipt))
        }
        return out
    }
}

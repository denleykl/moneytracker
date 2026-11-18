package com.mpesa.app

import java.time.LocalDateTime

/** One text message as read from the phone. `receivedAt` is used when the message has no date of its own. */
class SmsInput(val body: String, val receivedAt: LocalDateTime? = null)

class SmsResult(val rows: List<RawRow>, val unrecognised: List<String>, val skipped: Int)

/**
 * Reads M-Pesa confirmation messages and turns each into a RawRow written in the wording of a Safaricom statement,
 * so the normal parser, features and model are reused. Covered: sent to a person, paid to a till, paybill, received,
 * airtime, agent withdrawal, agent deposit, Fuliza repayment. Fuliza advances are skipped (the loan half of a payment).
 * The transaction-cost and balance parts of the message are ignored.
 * Written from the common message layouts: check it against your own messages and send me any it does not recognise.
 */
object MpesaSms {
    private const val AMT = "Ksh\\.?\\s?([\\d,]+(?:\\.\\d+)?)"
    private val receiptRe = Regex("^\\s*([A-Z0-9]{10})\\s+confirmed", RegexOption.IGNORE_CASE)
    private val whenRe = Regex("(\\d{1,2})/(\\d{1,2})/(\\d{2,4})\\s+at\\s+(\\d{1,2}):(\\d{2})\\s*(AM|PM)?", RegexOption.IGNORE_CASE)
    private val I = RegexOption.IGNORE_CASE

    private val received = Regex("You have received $AMT from (.+?)\\s+on\\s+\\d", I)
    private val sent     = Regex("$AMT sent to (.+?)\\s+on\\s+\\d", I)
    private val paid     = Regex("$AMT paid to (.+?)\\.?\\s*on\\s+\\d", I)
    private val airtime  = Regex("You bought $AMT of airtime", I)
    private val withdraw = Regex("Withdraw $AMT from (.+?)\\s+New", I)
    private val give     = Regex("Give $AMT cash to (.+?)\\s+New", I)
    private val fulizaRepay = Regex("$AMT from your M-PESA has been used to .*Fuliza", I)
    private val fulizaAdvance = Regex("Fuliza M-PESA amount is", I)
    private val forAccount = Regex("(.+?)\\s+for account\\s+(.+)", I)

    private fun num(s: String) = s.replace(",", "")

    fun parse(sms: SmsInput): RawRow? {
        val body = sms.body.replace(Regex("\\s+"), " ").trim()
        val code = receiptRe.find(body)?.groupValues?.get(1)?.uppercase() ?: return null
        val time = whenTime(body) ?: sms.receivedAt

        fun row(details: String, amount: String, inflow: Boolean) = RawRow(
            code, time?.let { "%04d-%02d-%02d %02d:%02d:%02d".format(it.year, it.monthValue, it.dayOfMonth, it.hour, it.minute, it.second) } ?: "",
            details, "Completed", if (inflow) num(amount) else "", if (inflow) "" else num(amount))

        if (fulizaAdvance.containsMatchIn(body)) return null
        received.find(body)?.let { return row("Funds received from - ${it.groupValues[2].trim()}", it.groupValues[1], true) }
        fulizaRepay.find(body)?.let { return row("OD Loan Repayment to 232323 - M-PESA Overdraw", it.groupValues[1], false) }
        airtime.find(body)?.let { return row("Airtime Purchase", it.groupValues[1], false) }
        withdraw.find(body)?.let { return row("Customer Withdrawal At Agent Till - ${it.groupValues[2].trim()}", it.groupValues[1], false) }
        give.find(body)?.let { return row("Deposit of Funds at Agent Till 0000000 - ${it.groupValues[2].trim()}", it.groupValues[1], true) }
        paid.find(body)?.let { return row("Merchant Payment to 0000000 - ${it.groupValues[2].trim().trimEnd('.')}", it.groupValues[1], false) }
        sent.find(body)?.let { m ->
            val who = m.groupValues[2].trim()
            val acc = forAccount.find(who)
            return if (acc != null) row("Pay Bill to 0000000 - ${acc.groupValues[1].trim()} Acc. ${acc.groupValues[2].trim()}", m.groupValues[1], false)
            else row("Customer Transfer to - $who", m.groupValues[1], false)
        }
        return null
    }

    /** "4/11/26 at 1:23 PM" (day/month/year) to a date-time, or null if the message has none. */
    private fun whenTime(body: String): LocalDateTime? {
        val m = whenRe.find(body) ?: return null
        return try {
            var h = m.groupValues[4].toInt(); val mm = m.groupValues[5].toInt()
            when (m.groupValues[6].uppercase()) { "PM" -> if (h < 12) h += 12; "AM" -> if (h == 12) h = 0 }
            var y = m.groupValues[3].toInt(); if (y < 100) y += 2000
            LocalDateTime.of(y, m.groupValues[2].toInt(), m.groupValues[1].toInt(), h, mm)
        } catch (_: Exception) { null }
    }

    fun parseAll(messages: List<SmsInput>): SmsResult {
        val rows = ArrayList<RawRow>(); val bad = ArrayList<String>(); var skipped = 0
        for (m in messages) {
            val b = m.body
            if (!receiptRe.containsMatchIn(b)) { skipped++; continue }            // not a confirmation (promos, balance checks...)
            if (fulizaAdvance.containsMatchIn(b)) { skipped++; continue }          // loan half of a Fuliza payment
            val r = parse(m)
            if (r != null) rows.add(r) else bad.add(b.replace(Regex("\\s+"), " ").take(160))
        }
        return SmsResult(rows, bad, skipped)
    }
}

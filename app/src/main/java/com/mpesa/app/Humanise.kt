package com.mpesa.app

import kotlin.math.atan2
import kotlin.math.expm1
import kotlin.math.PI

/** Turns a numeric model input into a sentence a person understands. Returns (label used for grouping, sentence). */
object Humanise {
    private fun n(v: Double) = Math.round(expm1(v)).toInt()
    private fun money(v: Double) = "KES " + "%,.0f".format(expm1(v))

    fun numeric(name: String, x: Map<String, Double>): Pair<String, String> {
        val v = x[name] ?: 0.0
        return when (name) {
            "log_amount" -> "amount" to "the amount (${money(v)})"
            "is_inflow" -> "direction" to if (v >= 0.5) "money came in" else "money went out"
            "hour_sin", "hour_cos" -> {
                var h = atan2(x["hour_sin"] ?: 0.0, x["hour_cos"] ?: 1.0) * 24 / (2 * PI); if (h < 0) h += 24
                "time" to "the time of day (around %02d:00)".format(Math.round(h).toInt() % 24)
            }
            "is_weekend" -> "weekend" to if (v >= 0.5) "it happened on a weekend" else "it happened on a weekday"
            "cp_count" -> "freq" to "you deal with them often (about ${n(v)} times)"
            "cp_active_days" -> "days" to "on about ${n(v)} different days"
            "cp_mean_amt" -> "typical" to "the usual amount is about ${money(v)}"
            "cp_amt_cv" -> "steady" to if (v < 0.3) "the amounts are very steady" else "the amounts vary a lot"
            "cp_inflow_share" -> "share" to when { v > 0.7 -> "money mostly comes in from them"; v < 0.3 -> "money mostly goes out to them"; else -> "money goes both ways" }
            "cp_gap_days" -> "gap" to "they repeat about every ${n(v)} days"
            "round_amount" -> "round" to if (v >= 0.5) "the amount is a round number" else "the amount is not a round number"
            "senders_today" -> "senders" to "${n(v)} different people paid you that day"
            "biz_name_flag" -> "bizname" to if (v >= 0.5) "the name sounds like a business" else "the name does not look like a business"
            else -> name to name
        }
    }
}

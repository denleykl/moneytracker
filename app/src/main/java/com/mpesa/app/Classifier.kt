package com.mpesa.app

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

data class Prediction(val label: String, val confidence: Double, val probabilities: DoubleArray) {
    /** Below this the app should ask the user instead of guessing (tune with section 10 of the notebook). */
    fun needsReview(threshold: Double = 0.7) = confidence < threshold
}

/**
 * Re-implementation of: ColumnTransformer(word TF-IDF, char_wb TF-IDF, MinMaxScaler) -> multinomial LogisticRegression.
 * No dense matrix is built: each term's weight is multiplied straight into the class scores.
 */
class TransactionClassifier(private val m: ModelData) {

    private val wordToken = Regex("\\b\\w\\w+\\b")          // scikit-learn's default token_pattern
    private val whitespace = Regex("\\s+")

    val classes: List<String> get() = m.classes

    fun predict(text: String, numericValues: Map<String, Double>): Prediction {
        val p = predictProba(text, numericValues)
        var best = 0
        for (k in p.indices) if (p[k] > p[best]) best = k
        return Prediction(m.classes[best], p[best], p)
    }

    fun predictProba(text: String, numericValues: Map<String, Double>): DoubleArray {
        val nC = m.classes.size
        val logits = DoubleArray(nC) { m.intercept[it].toDouble() }

        addTfidf(m.word, wordNgrams(text.lowercase(), m.word.minN, m.word.maxN), logits)
        addTfidf(m.char, charWbNgrams(text.lowercase(), m.char.minN, m.char.maxN), logits)

        for (f in m.numeric) {
            val x = numericValues[f.name] ?: error("Missing numeric feature '${f.name}'")
            val scaled = x * f.scale + f.min                  // MinMaxScaler.transform (no clipping, like scikit-learn)
            for (k in 0 until nC) logits[k] += scaled * f.coef[k]
        }
        return softmax(logits)
    }

    // ---- TF-IDF: counts -> 1 + ln(tf) -> * idf -> L2 normalise -> dot with coefficients ----
    private fun addTfidf(b: TfidfBlock, grams: List<String>, logits: DoubleArray) {
        val counts = HashMap<Int, Int>()
        for (g in grams) { val i = b.index[g] ?: continue; counts[i] = (counts[i] ?: 0) + 1 }
        if (counts.isEmpty()) return
        var sumSq = 0.0
        val w = HashMap<Int, Double>(counts.size * 2)
        for ((i, c) in counts) { val v = (1.0 + ln(c.toDouble())) * b.idf[i]; w[i] = v; sumSq += v * v }
        val norm = sqrt(sumSq)
        for ((i, v) in w) { val s = v / norm; for (k in logits.indices) logits[k] += s * b.coef[i][k] }
    }

    private fun wordNgrams(text: String, minN: Int, maxN: Int): List<String> {
        val tokens = wordToken.findAll(text).map { it.value }.toList()
        val out = ArrayList<String>()
        for (n in minN..maxN) for (i in 0..(tokens.size - n)) out.add(tokens.subList(i, i + n).joinToString(" "))
        return out
    }

    /** Mirrors sklearn's _char_wb_ngrams exactly, including the short-word rule. */
    private fun charWbNgrams(text: String, minN: Int, maxN: Int): List<String> {
        val out = ArrayList<String>()
        for (word in text.split(whitespace).filter { it.isNotEmpty() }) {
            val w = " $word "
            val len = w.length
            for (n in minN..maxN) {
                var offset = 0
                out.add(w.substring(offset, minOf(offset + n, len)))
                while (offset + n < len) { offset++; out.add(w.substring(offset, minOf(offset + n, len))) }
                if (offset == 0) break                       // word shorter than n: count it once
            }
        }
        return out
    }

    private fun softmax(z: DoubleArray): DoubleArray {
        val mx = z.max()
        val e = DoubleArray(z.size) { exp(z[it] - mx) }
        val s = e.sum()
        return DoubleArray(z.size) { e[it] / s }
    }
}

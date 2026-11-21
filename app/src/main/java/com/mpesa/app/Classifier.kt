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

    fun predictProba(text: String, numericValues: Map<String, Double>): DoubleArray = softmax(logits(text, numericValues))

    private fun logits(text: String, numericValues: Map<String, Double>): DoubleArray {
        val nC = m.classes.size
        val logits = DoubleArray(nC) { m.intercept[it].toDouble() }

        addTfidf(m.word, wordNgrams(text.lowercase(), m.word.minN, m.word.maxN), logits)
        addTfidf(m.char, charWbNgrams(text.lowercase(), m.char.minN, m.char.maxN), logits)

        for (f in m.numeric) {
            val x = numericValues[f.name] ?: error("Missing numeric feature '${f.name}'")
            val scaled = x * f.scale + f.min                  // MinMaxScaler.transform (no clipping, like scikit-learn)
            for (k in 0 until nC) logits[k] += scaled * f.coef[k]
        }
        return logits
    }

    // ---- TF-IDF: counts -> 1 + ln(tf) -> * idf -> L2 normalise -> dot with coefficients ----
    private fun addTfidf(b: TfidfBlock, grams: List<String>, logits: DoubleArray) {
        for ((i, s) in tfidfWeights(b, grams)) for (k in logits.indices) logits[k] += s * b.coef[i][k]
    }

    /** index -> normalised TF-IDF weight of every vocabulary term found in the text */
    private fun tfidfWeights(b: TfidfBlock, grams: List<String>): Map<Int, Double> {
        val counts = HashMap<Int, Int>()
        for (g in grams) { val i = b.index[g] ?: continue; counts[i] = (counts[i] ?: 0) + 1 }
        if (counts.isEmpty()) return emptyMap()
        var sumSq = 0.0
        val w = HashMap<Int, Double>(counts.size * 2)
        for ((i, c) in counts) { val v = (1.0 + ln(c.toDouble())) * b.idf[i]; w[i] = v; sumSq += v * v }
        val norm = sqrt(sumSq)
        return w.mapValues { it.value / norm }
    }

    // ---- Explanations: why this class and not the runner-up? ----
    private val structural = setOf("pay", "bill", "to", "num", "phone", "merchant", "payment", "customer", "transfer", "funds", "received", "from", "person", "of", "with")
    class Reason(val label: String, val detail: String, val contribution: Double)
    class Explanation(val predicted: String, val runnerUp: String, val margin: Double, val reasons: List<Reason>, val allContributions: Double)

    /**
     * Splits the score gap between the best class and the runner-up into the pieces that caused it
     * (words in the name, spelling, and the numeric facts). The pieces plus the intercept gap add up to the logit gap exactly.
     */
    fun explain(text: String, numericValues: Map<String, Double>, topK: Int = 3): Explanation {
        val lg = logits(text, numericValues)
        val order = lg.indices.sortedByDescending { lg[it] }
        val b = order[0]; val r = order[1]
        val parts = ArrayList<Reason>()
        var total = (m.intercept[b] - m.intercept[r]).toDouble()

        val lower = text.lowercase()
        var kind = 0.0
        for ((i, s) in tfidfWeights(m.word, wordNgrams(lower, m.word.minN, m.word.maxN))) {
            val d = s * (m.word.coef[i][b] - m.word.coef[i][r]); total += d
            val term = m.word.terms[i]
            if (term.split(" ").all { it in structural }) kind += d          // boilerplate such as "pay bill to": grouped, not shown word by word
            else parts.add(Reason("word:$term", "the name contains “$term”", d))
        }
        var chars = 0.0
        for ((i, s) in tfidfWeights(m.char, charWbNgrams(lower, m.char.minN, m.char.maxN))) chars += s * (m.char.coef[i][b] - m.char.coef[i][r])
        total += chars
        // People's names are hidden, so for transfers the text only says the direction: say that, not "spelling of the name".
        if (lower == "funds received from person" || lower == "customer transfer to person") {
            parts.add(Reason("kind", if (lower.startsWith("funds")) "it is a transfer from a person" else "it is a transfer to a person", kind + chars))
        } else {
            parts.add(Reason("kind", "of the kind of payment it is (till, paybill or transfer)", kind))
            parts.add(Reason("chars", "the spelling of the name looks like merchants of this kind", chars))
        }

        val byLabel = LinkedHashMap<String, Pair<String, Double>>()
        for (f in m.numeric) {
            val x = numericValues[f.name] ?: continue
            val d = (x * f.scale + f.min) * (f.coef[b] - f.coef[r]); total += d
            val (label, detail) = Humanise.numeric(f.name, numericValues)
            val old = byLabel[label]
            byLabel[label] = detail to ((old?.second ?: 0.0) + d)
        }
        byLabel.forEach { (label, v) -> parts.add(Reason(label, v.first, v.second)) }

        val top = parts.filter { it.contribution > 1e-9 }.sortedByDescending { it.contribution }.take(topK)
        return Explanation(m.classes[b], m.classes[r], lg[b] - lg[r], top, total)
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

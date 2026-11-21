package com.mpesa.app

/**
 * The "new name" guard from LogisticRegression_Final.ipynb, section 8.
 *
 * Problem it fixes: for a shop the model has never seen, it still answers confidently (mostly from the amount and words
 * like "merchant payment"), and a strong keyword rule (BETIKA, KPLC, ...) is only consulted when the model is unsure.
 * In the notebook's never-seen-shop test this raised the correct share of automatic answers from 45% to 87%.
 *
 * What it does, for rows that are NOT a person and were decided by the model or a rule
 * (your own answers, hand-labelled merchants and business patterns are left exactly as they are):
 *   1. a STRONG keyword rule wins over the model;
 *   2. otherwise, if the description has no informative word the model has ever seen, the row goes to the review queue
 *      (the model's guess is still shown as the suggestion).
 *
 * Use (no other file needs to change): build it once from the same model.bin, then wrap ReviewEngine.categorise:
 *     val guard = NameGuard(ModelIO.load(assets.open("model.bin")))
 *     val items = guard.apply(ReviewEngine.categorise(txs, classifier, corrections, ...))
 */
class NameGuard(model: ModelData) {
    /** every word and word pair the model learned */
    private val vocabulary: Set<String> = model.word.index.keys
    private val people = setOf("person_out", "person_in")
    private val wordToken = Regex("\\b\\w\\w+\\b")
    /** Words that only say how the money moved, not who it went to (same list as the notebook's BOILER). */
    private val boiler = setOf(
        "pay", "bill", "to", "num", "phone", "merchant", "payment", "customer", "transfer", "funds", "received", "from",
        "person", "of", "with", "online", "acc", "fuliza", "pesato", "pesa", "at", "till", "agent", "and")

    /** True if the model text contains no informative word from the model's vocabulary (a brand-new or unreadable name). */
    fun isNewName(text: String): Boolean {
        val words = wordToken.findAll(text.lowercase()).map { it.value }.filter { it !in boiler }.toList()
        return words.none { it in vocabulary }
    }

    fun apply(items: List<Categorised>): List<Categorised> = items.map { item ->
        if (item.tx.txGroup in people || (item.source != Source.MODEL && item.source != Source.RULE)) return@map item
        val hit = Rules.suggest(item.tx.txGroup, item.tx.name)
        when {
            hit != null && hit.confidence == Confidence.HIGH ->
                if (item.source == Source.RULE && item.category == hit.category && !item.needsReview) item
                else Categorised(item.tx, item.counterparty, hit.category, 0.85, Source.RULE, false, item.text, item.numeric, item.why)
            item.text.isNotEmpty() && isNewName(item.text) && !item.needsReview ->
                Categorised(item.tx, item.counterparty, item.category, item.confidence, item.source, true, item.text, item.numeric, item.why)
            else -> item
        }
    }
}

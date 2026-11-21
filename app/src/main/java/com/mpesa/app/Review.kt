package com.mpesa.app

/** Where a row's category came from. */
enum class Source { MODEL, USER, KNOWN, RULE, PATTERN }

/** One transaction with its final category. */
class Categorised(
    val tx: Transaction,
    val counterparty: String,
    val category: String,
    val confidence: Double,
    val source: Source,
    val needsReview: Boolean,
    /** the cleaned text and numeric inputs the model saw (kept so "Why?" can be answered later) */
    val text: String = "",
    val numeric: Map<String, Double> = emptyMap(),
    /** one-sentence reason for pattern-based decisions (price bands, restocking) */
    val why: String = "",
)

/** One question for the user: "what is this person/shop?" Covers every transaction with that counterparty. */
class ReviewItem(
    val counterparty: String,
    val displayName: String,
    val count: Int,
    val totalAmount: Double,
    val suggested: String,
    val confidence: Double,
    /** people are never matched by name similarity */
    val isPerson: Boolean = false,
)

/** Remembers what the user said about each counterparty. On Android back it with SharedPreferences or Room. */
interface CorrectionStore {
    fun get(counterparty: String): String?
    fun set(counterparty: String, category: String)
    fun all(): Map<String, String>
}

class InMemoryCorrections(initial: Map<String, String> = emptyMap()) : CorrectionStore {
    private val map = LinkedHashMap(initial)
    override fun get(counterparty: String) = map[counterparty]
    override fun set(counterparty: String, category: String) { map[counterparty] = category }
    override fun all(): Map<String, String> = map
}

/** Money grouping for the headline Business vs Personal view. */
enum class Bucket { BUSINESS, PERSONAL, OTHER }

/** Where each shilling goes on the dashboard. Business is split into income and expense by direction. */
enum class Flow { BUSINESS_INCOME, BUSINESS_EXPENSE, PERSONAL_IN, PERSONAL_SPEND, OTHER_IN, OTHER_OUT }

class CategoryTotal(val category: String, val count: Int, val moneyIn: Double, val moneyOut: Double)
class Summary(
    val byCategory: List<CategoryTotal>,
    val flows: Map<Flow, Double>,
    val transactions: Int,
    val needReview: Int,
    val moneyIn: Double,
    val moneyOut: Double,
) {
    fun flow(f: Flow): Double = flows.getValue(f)
    val netBusiness: Double get() = flow(Flow.BUSINESS_INCOME) - flow(Flow.BUSINESS_EXPENSE)
}

object ReviewEngine {
    const val DEFAULT_THRESHOLD = 0.7

    /** "Business - ..." and the P2P business class are business; "Personal - ..." is personal; loans, fees, cash and plain P2P are other. */
    fun bucketOf(category: String): Bucket = when {
        category.startsWith("Business") -> Bucket.BUSINESS
        category.startsWith("Personal") -> Bucket.PERSONAL
        else -> Bucket.OTHER
    }

    fun flowOf(category: String, inflow: Boolean): Flow = when (bucketOf(category)) {
        Bucket.BUSINESS -> if (inflow) Flow.BUSINESS_INCOME else Flow.BUSINESS_EXPENSE
        Bucket.PERSONAL -> if (inflow) Flow.PERSONAL_IN else Flow.PERSONAL_SPEND
        Bucket.OTHER -> if (inflow) Flow.OTHER_IN else Flow.OTHER_OUT
    }

    private val personGroups = setOf("person_out", "person_in")

    /**
     * Decides a category for every row, strongest evidence first:
     *  1. what the user told us about this counterparty
     *  2. a merchant you labelled by hand (known_merchants.csv)
     *  3. the model, if it is confident enough
     *  4. a keyword rule: a strong one is accepted; a weak one is accepted only if the model agrees with it
     *  5. otherwise ask the user (with the best guess shown)
     */
    fun categorise(
        txs: List<Transaction>,
        classifier: TransactionClassifier,
        corrections: CorrectionStore,
        threshold: Double = DEFAULT_THRESHOLD,
        known: KnownMerchants? = null,
        useRules: Boolean = true,
        business: BusinessProfile? = null,
    ): List<Categorised> {
        val feats = FeatureBuilder.build(txs)                 // whole statement at once
        return txs.indices.map { i ->
            val f = feats[i]; val tx = txs[i]
            val user = corrections.get(f.counterparty)
            val hand = if (user == null) known?.lookup(tx) else null
            when {
                user != null -> Categorised(tx, f.counterparty, user, 1.0, Source.USER, false, f.text, f.numeric)
                hand != null -> Categorised(tx, f.counterparty, hand, 1.0, Source.KNOWN, false, f.text, f.numeric)
                business?.match(tx, f.counterparty) != null -> {
                    val m = business.match(tx, f.counterparty)!!
                    Categorised(tx, f.counterparty, "Business - Sales income", m.confidence, Source.PATTERN, m.confidence < threshold, f.text, f.numeric, m.why)
                }
                else -> {
                    val p = classifier.predict(f.text, f.numeric)
                    val sup = business?.supplierHint(tx, f.counterparty)
                    if (sup != null && (p.confidence < sup.confidence || p.label == "Person-to-Person"))
                        return@map Categorised(tx, f.counterparty, "Business - Stock", sup.confidence, Source.PATTERN, true, f.text, f.numeric, sup.why)
                    if (p.confidence >= threshold) return@map Categorised(tx, f.counterparty, p.label, p.confidence, Source.MODEL, false, f.text, f.numeric)
                    val hit = if (useRules && tx.txGroup !in personGroups) Rules.suggest(tx.txGroup, tx.name) else null
                    when {
                        hit != null && hit.confidence == Confidence.HIGH -> Categorised(tx, f.counterparty, hit.category, 0.85, Source.RULE, false, f.text, f.numeric)
                        hit != null && hit.category == p.label -> Categorised(tx, f.counterparty, hit.category, 0.75, Source.RULE, false, f.text, f.numeric)
                        hit != null && p.confidence < 0.6 -> Categorised(tx, f.counterparty, hit.category, 0.6, Source.RULE, true, f.text, f.numeric)
                        else -> Categorised(tx, f.counterparty, p.label, p.confidence, Source.MODEL, true, f.text, f.numeric)
                    }
                }
            }
        }
    }

    /** Plain-language reasons for one row's category ("Why?"). */
    fun explain(item: Categorised, classifier: TransactionClassifier): List<String> {
        val who = if (item.tx.txGroup in personGroups) "person" else "merchant"
        return when (item.source) {
            Source.USER -> listOf("You told me this $who is “${item.category}”, so I use that for every payment to them.")
            Source.KNOWN -> listOf("You labelled this merchant as “${item.category}” earlier.")
            Source.PATTERN -> listOf(item.why, "If this is not what it looks like, change it once and I will remember.")
            Source.RULE -> listOf("The name matches a keyword rule" + (Rules.suggest(item.tx.txGroup, item.tx.name)?.let { ": ${it.why}" } ?: "") + ".",
                "I am ${"%.0f".format(item.confidence * 100)}% sure." + if (item.needsReview) " Please confirm." else "")
            Source.MODEL -> {
                if (item.text.isEmpty()) return listOf("I am ${"%.0f".format(item.confidence * 100)}% sure.")
                val e = classifier.explain(item.text, item.numeric)
                val lines = ArrayList<String>()
                lines.add("I think “${item.category}” (${"%.0f".format(item.confidence * 100)}% sure) rather than “${e.runnerUp}” because:")
                if (e.reasons.isEmpty()) lines.add("• no single clear signal; it is a close call")
                e.reasons.forEach { lines.add("• " + it.detail.replaceFirstChar { c -> c.uppercase() }) }
                lines
            }
        }
    }

    /** Questions to ask, biggest money first, one per counterparty. */
    fun reviewQueue(items: List<Categorised>): List<ReviewItem> =
        items.filter { it.needsReview }.groupBy { it.counterparty }.map { (cp, rows) ->
            val best = rows.maxByOrNull { it.confidence }!!
            ReviewItem(cp, Counterparty.displayName(rows[0].tx), rows.size, rows.sumOf { it.tx.amount }, best.category, best.confidence, rows[0].tx.txGroup in personGroups)
        }.sortedByDescending { it.totalAmount }

    fun summarise(items: List<Categorised>): Summary {
        val byCat = items.groupBy { it.category }.map { (c, rows) ->
            CategoryTotal(c, rows.size, rows.filter { it.tx.isInflow }.sumOf { it.tx.amount }, rows.filter { !it.tx.isInflow }.sumOf { it.tx.amount })
        }.sortedByDescending { it.moneyIn + it.moneyOut }
        val flows = Flow.entries.associateWith { 0.0 }.toMutableMap()
        for (r in items) { val f = flowOf(r.category, r.tx.isInflow); flows[f] = flows.getValue(f) + r.tx.amount }
        return Summary(byCat, flows, items.size, items.count { it.needsReview },
            items.filter { it.tx.isInflow }.sumOf { it.tx.amount }, items.filter { !it.tx.isInflow }.sumOf { it.tx.amount })
    }
}

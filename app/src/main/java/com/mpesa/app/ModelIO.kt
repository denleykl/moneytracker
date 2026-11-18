package com.mpesa.app

import java.io.DataInputStream
import java.io.InputStream

/** One TF-IDF vectoriser plus the logistic-regression weights that belong to its terms. */
class TfidfBlock(
    val minN: Int,
    val maxN: Int,
    val index: HashMap<String, Int>,
    val idf: FloatArray,
    /** coef[term][class] */
    val coef: Array<FloatArray>,
)

class NumericFeature(val name: String, val min: Float, val scale: Float, val coef: FloatArray)

/** Everything exported by export_model.py. Immutable once loaded, safe to share between threads. */
class ModelData(
    val classes: List<String>,
    val intercept: FloatArray,
    val word: TfidfBlock,
    val char: TfidfBlock,
    val numeric: List<NumericFeature>,
) {
    val numNames: List<String> get() = numeric.map { it.name }
}

object ModelIO {
    private const val MAGIC = 0x4D504D32
    private const val VERSION = 1

    /** On Android: ModelIO.load(context.assets.open("model.bin")) */
    fun load(input: InputStream): ModelData = DataInputStream(input.buffered()).use { d ->
        require(d.readInt() == MAGIC) { "Not a model.bin file" }
        require(d.readInt() == VERSION) { "Unsupported model version" }
        val nClasses = d.readInt()
        val classes = List(nClasses) { d.readUTF() }
        val intercept = FloatArray(nClasses) { d.readFloat() }

        fun block(): TfidfBlock {
            val minN = d.readInt(); val maxN = d.readInt(); val n = d.readInt()
            val index = HashMap<String, Int>(n * 2)
            val idf = FloatArray(n)
            val coef = Array(n) { FloatArray(nClasses) }
            for (i in 0 until n) {
                index[d.readUTF()] = i
                idf[i] = d.readFloat()
                for (k in 0 until nClasses) coef[i][k] = d.readFloat()
            }
            return TfidfBlock(minN, maxN, index, idf, coef)
        }

        val word = block()
        val char = block()
        val nNum = d.readInt()
        val numeric = List(nNum) {
            val name = d.readUTF(); val mn = d.readFloat(); val sc = d.readFloat()
            NumericFeature(name, mn, sc, FloatArray(nClasses) { d.readFloat() })
        }
        ModelData(classes, intercept, word, char, numeric)
    }
}

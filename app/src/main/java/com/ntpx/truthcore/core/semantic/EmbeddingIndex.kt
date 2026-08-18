package com.ntpx.truthcore.core.semantic

import kotlin.math.sqrt

data class EmbeddingVector(
    val available: Boolean,
    val values: FloatArray = floatArrayOf(),
    val provider: String = "unavailable",
    val reason: String,
)

fun interface EmbeddingProvider {
    fun embed(text: String): EmbeddingVector
}

data class SemanticMatch(val id: String, val score: Double)

class EmbeddingIndex(private val provider: EmbeddingProvider? = null) {
    private val vectors = linkedMapOf<String, FloatArray>()

    fun add(id: String, text: String): Boolean {
        val engine = provider ?: return false
        val vector = engine.embed(text)
        if (!vector.available || vector.values.isEmpty()) return false
        vectors[id] = vector.values.copyOf()
        return true
    }

    fun search(text: String, limit: Int = 8): List<SemanticMatch> {
        val engine = provider ?: return emptyList()
        val query = engine.embed(text)
        if (!query.available || query.values.isEmpty()) return emptyList()
        return vectors.mapNotNull { (id, vector) -> cosine(query.values, vector)?.let { SemanticMatch(id, it) } }
            .sortedByDescending { it.score }.take(limit)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double? {
        if (a.isEmpty() || a.size != b.size) return null
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        if (aa == 0.0 || bb == 0.0) return null
        return dot / (sqrt(aa) * sqrt(bb))
    }
}

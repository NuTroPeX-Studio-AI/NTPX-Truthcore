package com.ntpx.truthcore.core.semantic

/** Semantic verification never invents confidence when no provider is installed. */
data class SemanticScore(
    val available: Boolean,
    val entailment: Double = 0.0,
    val contradiction: Double = 0.0,
    val provider: String = "unavailable",
    val reason: String,
)

fun interface NliProvider {
    fun score(claim: String, evidence: String): SemanticScore
}

class SemanticVerifier(private val provider: NliProvider? = null) {
    fun verify(claim: String, evidence: String): SemanticScore {
        if (claim.isBlank() || evidence.isBlank()) {
            return SemanticScore(false, reason = "Claim or evidence is empty")
        }
        val engine = provider ?: return SemanticScore(
            available = false,
            reason = "No NLI provider is configured; semantic confidence is unavailable",
        )
        val result = engine.score(claim, evidence)
        if (!result.available) return result
        return result.copy(
            entailment = result.entailment.coerceIn(0.0, 1.0),
            contradiction = result.contradiction.coerceIn(0.0, 1.0),
        )
    }
}

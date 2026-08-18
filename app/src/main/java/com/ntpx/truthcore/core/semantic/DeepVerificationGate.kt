package com.ntpx.truthcore.core.semantic

import com.ntpx.truthcore.core.evidence.Evidence
import com.ntpx.truthcore.core.evidence.EvidenceSanitizer

data class DeepVerificationResult(
    val releasable: Boolean,
    val semanticAvailable: Boolean,
    val reason: String,
    val semantic: SemanticScore? = null,
)

class DeepVerificationGate(
    private val semanticVerifier: SemanticVerifier,
    private val entailmentThreshold: Double = 0.80,
    private val contradictionThreshold: Double = 0.20,
) {
    fun assess(claim: String, evidence: Evidence): DeepVerificationResult {
        if (evidence.effectiveStrength() <= 0.0) {
            return DeepVerificationResult(false, false, "Evidence is expired, quarantined, or untrusted")
        }

        val sanitized = EvidenceSanitizer.sanitize(evidence.content)
        if (sanitized.quarantined || sanitized.text.isBlank()) {
            return DeepVerificationResult(false, false, "Evidence failed injection isolation")
        }

        val semantic = semanticVerifier.verify(claim, sanitized.text)
        if (!semantic.available) {
            return DeepVerificationResult(false, false, semantic.reason, semantic)
        }
        if (semantic.contradiction >= contradictionThreshold) {
            return DeepVerificationResult(false, true, "Semantic verifier detected contradiction", semantic)
        }
        if (semantic.entailment < entailmentThreshold) {
            return DeepVerificationResult(false, true, "Semantic entailment is below release threshold", semantic)
        }
        return DeepVerificationResult(true, true, "Semantic evidence passed deep verification", semantic)
    }
}

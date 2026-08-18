package com.ntpx.truthcore.core.semantic

import com.ntpx.truthcore.core.evidence.Evidence
import org.junit.Assert.*
import org.junit.Test

class DeepVerificationGateTest {
    @Test fun missingNliProviderFailsClosed() {
        val gate = DeepVerificationGate(SemanticVerifier())
        val result = gate.assess("Paris is in France", Evidence("1", "s", "Paris is in France"))
        assertFalse(result.releasable)
        assertFalse(result.semanticAvailable)
    }

    @Test fun strongEntailmentCanPass() {
        val verifier = SemanticVerifier(NliProvider { _, _ ->
            SemanticScore(true, entailment = 0.95, contradiction = 0.01, provider = "fixture", reason = "test")
        })
        val result = DeepVerificationGate(verifier).assess(
            "Paris is in France",
            Evidence("1", "s", "Paris is in France")
        )
        assertTrue(result.releasable)
        assertTrue(result.semanticAvailable)
    }

    @Test fun contradictionBlocksRelease() {
        val verifier = SemanticVerifier(NliProvider { _, _ ->
            SemanticScore(true, entailment = 0.60, contradiction = 0.90, provider = "fixture", reason = "test")
        })
        val result = DeepVerificationGate(verifier).assess("claim", Evidence("1", "s", "evidence"))
        assertFalse(result.releasable)
    }
}

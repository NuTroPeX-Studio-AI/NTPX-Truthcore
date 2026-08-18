package com.ntpx.truthcore.core.semantic

import org.junit.Assert.*
import org.junit.Test

class SemanticVerifierTest {
    @Test fun unavailableProviderDoesNotInventConfidence() {
        val result = SemanticVerifier().verify("Paris is in France", "Paris is in France")
        assertFalse(result.available)
        assertEquals(0.0, result.entailment, 0.0)
        assertEquals(0.0, result.contradiction, 0.0)
    }

    @Test fun providerScoresAreClamped() {
        val verifier = SemanticVerifier(NliProvider { _, _ ->
            SemanticScore(true, entailment = 1.4, contradiction = -0.2, provider = "test", reason = "fixture")
        })
        val result = verifier.verify("claim", "evidence")
        assertTrue(result.available)
        assertEquals(1.0, result.entailment, 0.0)
        assertEquals(0.0, result.contradiction, 0.0)
    }
}

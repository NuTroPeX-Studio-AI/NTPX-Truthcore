package com.ntpx.truthcore.core.semantic

import com.ntpx.truthcore.core.evidence.Evidence
import com.ntpx.truthcore.core.truth.ClaimAssessment
import com.ntpx.truthcore.core.truth.ClaimLock
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SemanticVerificationTest {
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
        assertEquals(1.0, result.entailment, 0.0)
        assertEquals(0.0, result.contradiction, 0.0)
    }

    @Test fun strongEntailmentCanPassDeepGate() {
        val verifier = SemanticVerifier(NliProvider { _, _ ->
            SemanticScore(true, entailment = 0.95, contradiction = 0.01, provider = "fixture", reason = "test")
        })
        val result = DeepVerificationGate(verifier).assess("Paris is in France", Evidence("1", "s", "Paris is in France"))
        assertTrue(result.releasable)
    }

    @Test fun contradictionBlocksDeepGate() {
        val verifier = SemanticVerifier(NliProvider { _, _ ->
            SemanticScore(true, entailment = 0.6, contradiction = 0.9, provider = "fixture", reason = "test")
        })
        assertFalse(DeepVerificationGate(verifier).assess("claim", Evidence("1", "s", "evidence")).releasable)
    }

    @Test fun claimLockCanRequireSemanticVerification() {
        val evidence = listOf(Evidence("1", "s", "Paris is in France", trust = 1.0))
        val passGate = DeepVerificationGate(SemanticVerifier(NliProvider { _, _ ->
            SemanticScore(true, entailment = 0.99, contradiction = 0.0, provider = "fixture", reason = "test")
        }))
        val passed = ClaimLock.verify("Paris is in France [S1].", evidence, semanticGate = passGate, requireSemantic = true)
        assertEquals(ClaimAssessment.Status.SUPPORTED, passed.claims.single().status)

        val absentGate = DeepVerificationGate(SemanticVerifier())
        val withheld = ClaimLock.verify("Paris is in France [S1].", evidence, semanticGate = absentGate, requireSemantic = true)
        assertEquals(ClaimAssessment.Status.UNSUPPORTED, withheld.claims.single().status)
    }

    @Test fun semanticContradictionBlocksClaimLockEvenWhenLexicalOverlapIsHigh() {
        val evidence = listOf(Evidence("1", "s", "Paris is in France", trust = 1.0))
        val gate = DeepVerificationGate(SemanticVerifier(NliProvider { _, _ ->
            SemanticScore(true, entailment = 0.85, contradiction = 0.95, provider = "fixture", reason = "test")
        }))
        val result = ClaimLock.verify("Paris is in France [S1].", evidence, semanticGate = gate)
        assertEquals(ClaimAssessment.Status.CONTRADICTED, result.claims.single().status)
    }

    @Test fun embeddingIndexFailsClosedAndCanSearchWhenProviderExists() {
        assertTrue(EmbeddingIndex().search("hello").isEmpty())
        val provider = EmbeddingProvider { text ->
            val vector = if (text.contains("android", true)) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
            EmbeddingVector(true, vector, "fixture", "test")
        }
        val index = EmbeddingIndex(provider)
        assertTrue(index.add("android", "Android native runtime"))
        assertTrue(index.add("other", "Unrelated topic"))
        assertEquals("android", index.search("Android app").first().id)
    }

    @Test fun temporalGraphSurfacesConflicts() {
        val graph = TemporalKnowledgeGraph()
        val now = Instant.parse("2026-01-01T00:00:00Z")
        graph.add(TemporalFact("a", "x", "state", "one", now.minusSeconds(60), null, "s1", 0.90))
        graph.add(TemporalFact("b", "x", "state", "two", now.minusSeconds(30), null, "s2", 0.82))
        val resolved = graph.resolve("x", "state", now)
        assertEquals(1, resolved.conflicts.size)
        assertFalse(ContradictionResolver.resolve(resolved, 0.15).resolved)
    }

    @Test fun sourceAuthenticationRequiresHttpsOrLocalMemory() {
        assertTrue(SourceAuthenticator.verify(Evidence("1", "s", "x", sourceUri = "https://example.com/fact")).authenticated)
        assertTrue(SourceAuthenticator.verify(Evidence("2", "s", "x", sourceUri = "memory://abc")).authenticated)
        assertFalse(SourceAuthenticator.verify(Evidence("3", "s", "x", sourceUri = "http://example.com/fact")).authenticated)
    }
}

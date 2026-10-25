package com.ntpx.truthcore.core.truth

import com.ntpx.truthcore.core.evidence.Evidence
import com.ntpx.truthcore.core.semantic.DeepVerificationGate
import com.ntpx.truthcore.core.semantic.NliProvider
import com.ntpx.truthcore.core.semantic.SemanticScore
import com.ntpx.truthcore.core.semantic.SemanticVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ClaimLockHardeningTest {
    private val paris = Evidence("a", "source", "Paris is the capital of France.", trust = 1.0)

    @Test fun citationOnlyFragmentIsReattachedToItsSentence() {
        val result = ClaimLock.verify("Paris is the capital of France. [S1]", listOf(paris))
        assertEquals(1, result.claims.size)
        assertEquals(ClaimAssessment.Status.SUPPORTED, result.claims.single().status)
    }

    @Test fun stopwordOnlyClaimCannotRideACitation() {
        val result = ClaimLock.verify("It is. [S1]", listOf(paris))
        assertEquals(0, result.released)
        assertEquals("Claim has no checkable content", result.claims.single().reason)
    }

    @Test fun unrelatedNegationElsewhereInSourceDoesNotFlagContradiction() {
        val source = Evidence("a", "doc", "Paris is the capital of France.\nThe museum is not open on Tuesdays.", trust = 1.0)
        val result = ClaimLock.verify("Paris is the capital of France [S1].", listOf(source))
        assertEquals(ClaimAssessment.Status.SUPPORTED, result.claims.single().status)
    }

    @Test fun polarityFlipInMatchingSentenceIsContradicted() {
        val source = Evidence("a", "doc", "The service does not store audio recordings.", trust = 1.0)
        val result = ClaimLock.verify("The service does store audio recordings [S1].", listOf(source))
        assertEquals(ClaimAssessment.Status.CONTRADICTED, result.claims.single().status)
    }

    @Test fun thousandsSeparatorsAreNormalized() {
        val source = Evidence("a", "doc", "The archive holds 12000 records.", trust = 1.0)
        val result = ClaimLock.verify("The archive holds 12,000 records [S1].", listOf(source))
        assertEquals(ClaimAssessment.Status.SUPPORTED, result.claims.single().status)
    }

    @Test fun percentagesMustMatchExactly() {
        val source = Evidence("a", "doc", "Uptime was 99.9% last quarter.", trust = 1.0)
        assertEquals(ClaimAssessment.Status.SUPPORTED, ClaimLock.verify("Uptime was 99.9% last quarter [S1].", listOf(source)).claims.single().status)
        assertEquals(0, ClaimLock.verify("Uptime was 99.5% last quarter [S1].", listOf(source)).released)
    }

    @Test fun abbreviationsDoNotSplitStatements() {
        val parts = ClaimLock.statements("Use a provider, e.g. a local model [S1]. Then verify [S2].")
        assertEquals(2, parts.size)
        assertTrue(parts.first().contains("e.g. a local model"))
    }

    @Test fun expiredEvidenceCannotSupport() {
        val expired = paris.copy(expiresAt = Instant.EPOCH)
        assertEquals(0, ClaimLock.verify("Paris is the capital of France [S1].", listOf(expired)).released)
    }

    @Test fun outOfRangeCitationFailsClosed() {
        assertEquals(0, ClaimLock.verify("Paris is the capital of France [S2].", listOf(paris)).released)
    }

    @Test fun semanticPassCannotOverrideDeterministicRejection() {
        val alwaysEntails = DeepVerificationGate(SemanticVerifier(NliProvider { _, _ ->
            SemanticScore(true, entailment = 1.0, contradiction = 0.0, provider = "fixture", reason = "test")
        }))
        val result = ClaimLock.verify("Berlin hosts the parliament of Germany [S1].", listOf(paris), semanticGate = alwaysEntails)
        assertEquals(0, result.released)
        assertEquals(ClaimAssessment.Status.UNSUPPORTED, result.claims.single().status)
    }

    @Test fun lowSemanticEntailmentWithholdsLexicallySupportedClaim() {
        val weak = DeepVerificationGate(SemanticVerifier(NliProvider { _, _ ->
            SemanticScore(true, entailment = 0.4, contradiction = 0.05, provider = "fixture", reason = "test")
        }))
        val result = ClaimLock.verify("Paris is the capital of France [S1].", listOf(paris), semanticGate = weak)
        assertEquals(ClaimAssessment.Status.UNSUPPORTED, result.claims.single().status)
    }

    @Test fun unavailableOptionalGateKeepsDeterministicResult() {
        val absent = DeepVerificationGate(SemanticVerifier())
        val result = ClaimLock.verify("Paris is the capital of France [S1].", listOf(paris), semanticGate = absent)
        assertEquals(ClaimAssessment.Status.SUPPORTED, result.claims.single().status)
        assertTrue(result.claims.single().reason.contains("semantic gate unavailable"))
    }

    @Test fun semanticPassIsRecordedAsMethod() {
        val strong = DeepVerificationGate(SemanticVerifier(NliProvider { _, _ ->
            SemanticScore(true, entailment = 0.97, contradiction = 0.01, provider = "fixture", reason = "test")
        }))
        val claim = ClaimLock.verify("Paris is the capital of France [S1].", listOf(paris), semanticGate = strong).claims.single()
        assertEquals("semantic+deterministic", claim.method)
    }
}

package com.ntpx.truthcore.core.chat

import com.ntpx.truthcore.core.evidence.Evidence
import com.ntpx.truthcore.core.model.ModelProvider
import com.ntpx.truthcore.core.model.ModelRequest
import com.ntpx.truthcore.core.model.ModelResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationEngineTest {
    private val engine = ConversationEngine()

    @Test fun helpReturnsVerifiedLocalCapabilities() {
        val reply = engine.respond("help")
        assertTrue(reply.verified)
        assertEquals("VERIFIED", reply.status)
        assertTrue(reply.text.contains("native Android and web clients"))
        assertTrue(reply.text.contains("persistent memory"))
        assertTrue(reply.text.contains("permissioned agent tools"))
    }

    @Test fun statusReturnsOnlyBoundLocalFacts() {
        val reply = engine.respond("status")
        assertTrue(reply.verified)
        assertEquals("VERIFIED", reply.status)
        assertTrue(reply.text.contains("ClaimLock"))
        assertTrue(reply.text.contains("audit ledger"))
    }

    @Test fun unknownTopicWithoutProviderAbstainsInsteadOfInventing() {
        val reply = engine.respond("Who won a game today?")
        assertFalse(reply.verified)
        assertEquals("ABSTAINED", reply.status)
        assertTrue(reply.text.contains("No model provider is connected"))
    }

    @Test fun retrievedEvidenceIsPassedToFactualModelAndCanRelease() {
        val dynamic = Evidence("dynamic", "Saved fact", "The project codename is Orion.", trust = 1.0)
        val dynamicEngine = ConversationEngine { listOf(dynamic) }
        val provider = fixtureProvider("The project codename is Orion [S4].")
        val reply = dynamicEngine.respond("What is the project codename?", provider)
        assertTrue(reply.verified)
        assertEquals("VERIFIED", reply.status)
        assertTrue(reply.text.contains("Orion"))
    }

    @Test fun creativeRequestUsesConnectedProviderAsGeneratedOutput() {
        val reply = engine.respond("write a short greeting", fixtureProvider("A short original greeting."))
        assertFalse(reply.verified)
        assertEquals("GENERATED", reply.status)
        assertEquals("A short original greeting.", reply.text)
    }

    @Test fun unsupportedFactualModelDraftIsWithheld() {
        val reply = engine.respond("What is the moon made of?", fixtureProvider("The moon is made of cheese."))
        assertFalse(reply.verified)
        assertEquals("ABSTAINED", reply.status)
        assertTrue(reply.text.contains("enough verified evidence"))
    }

    @Test fun providerFailureIsSurfacedWithoutClaimingVerification() {
        val provider = object : ModelProvider {
            override val displayName = "fixture"
            override fun generate(request: ModelRequest) = ModelResponse(false, error = "offline")
        }
        val reply = engine.respond("What is outside?", provider)
        assertFalse(reply.verified)
        assertEquals("PROVIDER_ERROR", reply.status)
        assertTrue(reply.text.contains("offline"))
    }

    private fun fixtureProvider(text: String) = object : ModelProvider {
        override val displayName = "fixture"
        override fun generate(request: ModelRequest) = ModelResponse(true, text = text)
    }
}

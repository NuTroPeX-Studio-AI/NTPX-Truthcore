package com.ntpx.truthcore.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationEngineTest {
    private val engine = ConversationEngine()

    @Test
    fun helpReturnsVerifiedLocalCapabilities() {
        val reply = engine.respond("help")
        assertTrue(reply.verified)
        assertEquals("VERIFIED", reply.status)
        assertTrue(reply.text.contains("native Android interface"))
    }

    @Test
    fun statusReturnsOnlyBoundLocalFacts() {
        val reply = engine.respond("status")
        assertTrue(reply.verified)
        assertEquals("VERIFIED", reply.status)
        assertTrue(reply.text.contains("ClaimLock"))
        assertTrue(reply.text.contains("production model provider"))
    }

    @Test
    fun unknownTopicAbstainsInsteadOfInventing() {
        val reply = engine.respond("Who won a game today?")
        assertFalse(reply.verified)
        assertEquals("ABSTAINED", reply.status)
        assertTrue(reply.text.contains("won't invent an answer"))
    }
}

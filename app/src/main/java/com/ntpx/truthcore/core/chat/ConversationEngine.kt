package com.ntpx.truthcore.core.chat

import com.ntpx.truthcore.core.evidence.Evidence
import com.ntpx.truthcore.core.truth.ClaimLock

data class ConversationReply(
    val text: String,
    val verified: Boolean,
    val status: String,
)

class ConversationEngine {
    private val localEvidence = listOf(
        Evidence(
            id = "local-architecture",
            label = "TruthCore architecture",
            content = "TruthCore uses ClaimLock to withhold unsupported factual claims. ClaimLock is active in TruthCore.",
            trust = 1.0,
        ),
        Evidence(
            id = "local-capabilities",
            label = "TruthCore Android capabilities",
            content = "TruthCore Android is running locally. TruthCore v0.5.1 includes a native Android interface, microphone speech recognition, text to speech, local ClaimLock verification, evidence primitives, and memory primitives.",
            trust = 1.0,
        ),
        Evidence(
            id = "local-provider-status",
            label = "TruthCore model provider status",
            content = "TruthCore does not yet have a production model provider connected in v0.5.1.",
            trust = 1.0,
        ),
    )

    fun respond(input: String): ConversationReply {
        val request = input.trim()
        if (request.isBlank()) {
            return ConversationReply("Enter or speak a request first.", verified = true, status = "LOCAL")
        }

        val draft = when {
            request.contains("claimlock", ignoreCase = true) ->
                "TruthCore uses ClaimLock to withhold unsupported factual claims [S1]."

            request.contains("what can you do", ignoreCase = true) ||
                request.equals("help", ignoreCase = true) ->
                "TruthCore v0.5.1 includes a native Android interface, microphone speech recognition, text to speech, local ClaimLock verification, evidence primitives, and memory primitives [S2]."

            request.contains("model", ignoreCase = true) ||
                request.contains("provider", ignoreCase = true) ||
                request.contains("online", ignoreCase = true) ->
                "TruthCore does not yet have a production model provider connected in v0.5.1 [S3]."

            request.contains("status", ignoreCase = true) ->
                "TruthCore Android is running locally [S2]. ClaimLock is active in TruthCore [S1]. TruthCore does not yet have a production model provider connected in v0.5.1 [S3]."

            else -> null
        }

        if (draft == null) {
            return ConversationReply(
                text = "I received your request, but no production model provider or supporting evidence is connected for that topic yet. I won't invent an answer.",
                verified = false,
                status = "ABSTAINED",
            )
        }

        val result = ClaimLock.verify(draft, localEvidence)
        val released = result.released > 0 && result.withheld == 0
        return ConversationReply(
            text = result.answer,
            verified = released,
            status = if (released) "VERIFIED" else "ABSTAINED",
        )
    }
}

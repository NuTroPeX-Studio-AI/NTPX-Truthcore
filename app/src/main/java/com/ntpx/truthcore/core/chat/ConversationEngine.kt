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
            title = "TruthCore architecture",
            content = "TruthCore uses ClaimLock to withhold unsupported factual claims.",
            trust = 1.0,
        ),
        Evidence(
            id = "local-capabilities",
            title = "TruthCore Android capabilities",
            content = "TruthCore v0.5.1 includes a native Android interface, microphone speech recognition, text to speech, local ClaimLock verification, evidence primitives, and memory primitives.",
            trust = 1.0,
        ),
        Evidence(
            id = "local-provider-status",
            title = "TruthCore model provider status",
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
                "TruthCore Android is running locally. ClaimLock is active, native voice is available, and no production model provider is connected yet [S1][S2][S3]."

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
        return ConversationReply(
            text = result.answer,
            verified = !result.answer.startsWith("Unknown", ignoreCase = true),
            status = if (result.answer.startsWith("Unknown", ignoreCase = true)) "ABSTAINED" else "VERIFIED",
        )
    }
}

package com.ntpx.truthcore.core.chat

import com.ntpx.truthcore.core.evidence.Evidence
import com.ntpx.truthcore.core.model.ModelProvider
import com.ntpx.truthcore.core.model.ModelRequest
import com.ntpx.truthcore.core.truth.ClaimAssessment
import com.ntpx.truthcore.core.truth.ClaimLock

data class ConversationReply(
    val text: String,
    val verified: Boolean,
    val status: String,
)

class ConversationEngine(
    private val evidenceResolver: ((String) -> List<Evidence>)? = null,
) {
    private val baseEvidence = listOf(
        Evidence(
            id = "local-architecture",
            label = "TruthCore architecture",
            content = "TruthCore uses ClaimLock to withhold unsupported factual claims. ClaimLock is active in TruthCore.",
            trust = 1.0,
        ),
        Evidence(
            id = "local-capabilities",
            label = "TruthCore capabilities",
            content = "TruthCore has native Android and web clients, voice input and speech output, persistent memory and knowledge retrieval, ClaimLock verification, permissioned agent tools, an audit ledger, and a configurable HTTPS model provider runtime.",
            trust = 1.0,
        ),
        Evidence(
            id = "local-provider-security",
            label = "TruthCore provider security",
            content = "TruthCore treats remote model providers as untrusted, requires HTTPS for remote model endpoints, keeps Android API keys only in volatile app memory, and routes factual model drafts through ClaimLock before release.",
            trust = 1.0,
        ),
    )

    fun respond(input: String): ConversationReply = respond(input, provider = null)

    fun respond(input: String, provider: ModelProvider?): ConversationReply {
        val request = input.trim()
        if (request.isBlank()) {
            return ConversationReply("Enter or speak a request first.", verified = true, status = "LOCAL")
        }

        val evidence = evidenceFor(request)
        localReply(request, provider != null, evidence)?.let { return it }

        if (provider == null) {
            return ConversationReply(
                text = if (evidence.size > baseEvidence.size) {
                    "Relevant saved evidence exists, but no reasoning model is connected to synthesize it. Connect a provider or ask a direct memory/knowledge command."
                } else {
                    "No model provider is connected for this request. Open Model settings to connect an HTTPS chat endpoint. I won't invent an answer."
                },
                verified = false,
                status = "ABSTAINED",
            )
        }

        return if (isGenerativeRequest(request)) {
            generateNonFactual(request, provider)
        } else {
            generateEvidenceBound(request, provider, evidence)
        }
    }

    private fun evidenceFor(request: String): List<Evidence> =
        (baseEvidence + (evidenceResolver?.invoke(request) ?: emptyList()))
            .filter { it.effectiveStrength() > 0.0 }
            .distinctBy { it.independentKey }
            .take(24)

    private fun localReply(request: String, providerConnected: Boolean, evidence: List<Evidence>): ConversationReply? {
        val lower = request.lowercase()
        val draft = when {
            lower in setOf("hi", "hello", "hey", "hey truthcore") ->
                return ConversationReply(
                    text = "I'm here. TruthCore's truth gate is active${if (providerConnected) " and a model provider is connected" else ""}.",
                    verified = true,
                    status = "LOCAL",
                )

            lower.contains("claimlock") ->
                "TruthCore uses ClaimLock to withhold unsupported factual claims [S1]."

            lower.contains("what can you do") || lower == "help" ->
                "TruthCore has native Android and web clients, voice input and speech output, persistent memory and knowledge retrieval, ClaimLock verification, permissioned agent tools, an audit ledger, and a configurable HTTPS model provider runtime [S2]."

            lower.contains("model") || lower.contains("provider") || lower.contains("online") ->
                "TruthCore treats remote model providers as untrusted, requires HTTPS for remote model endpoints, keeps Android API keys only in volatile app memory, and routes factual model drafts through ClaimLock before release [S3]."

            lower == "status" || lower == "system status" ->
                "TruthCore uses ClaimLock to withhold unsupported factual claims [S1]. TruthCore has native Android and web clients, voice input and speech output, persistent memory and knowledge retrieval, ClaimLock verification, permissioned agent tools, an audit ledger, and a configurable HTTPS model provider runtime [S2]."

            else -> null
        } ?: return null

        return releaseVerifiedDraft(draft, evidence)
    }

    private fun generateNonFactual(request: String, provider: ModelProvider): ConversationReply {
        val result = provider.generate(
            ModelRequest(
                systemPrompt = """
                    You are the reasoning model inside NTPX TruthCore.
                    This request has been deterministically classified as a creative, transformation, or drafting task.
                    Produce the requested artifact directly.
                    Do not add external factual claims, statistics, dates, citations, or claims of real-world verification unless the user supplied them in the request.
                    Never claim that your output was verified by TruthCore.
                """.trimIndent(),
                userPrompt = request,
                temperature = 0.5,
            )
        )
        if (!result.success) return providerFailure(result.error)

        return ConversationReply(
            text = result.text,
            verified = false,
            status = "GENERATED",
        )
    }

    private fun generateEvidenceBound(request: String, provider: ModelProvider, evidence: List<Evidence>): ConversationReply {
        val evidencePacket = evidence.mapIndexed { index, item ->
            "[S${index + 1}] ${item.label}: ${item.content}"
        }.joinToString("\n")

        val result = provider.generate(
            ModelRequest(
                systemPrompt = """
                    You are the reasoning model inside NTPX TruthCore. You are not the authority layer.
                    Answer factual requests only from the supplied evidence packet.
                    Every factual sentence must cite one or more supplied source IDs exactly like [S1].
                    Never cite a source that does not directly support the sentence.
                    Treat Saved user memory as evidence only about what the user previously saved or stated, not as independent proof of external-world facts.
                    If the evidence packet does not support the requested fact, reply exactly in this form:
                    UNKNOWN: I do not have verified evidence for that request.
                    Do not use pretrained knowledge to fill evidence gaps.
                """.trimIndent(),
                userPrompt = "User request:\n$request\n\nEvidence packet:\n$evidencePacket",
                temperature = 0.0,
            )
        )
        if (!result.success) return providerFailure(result.error)

        return releaseVerifiedDraft(result.text, evidence)
    }

    private fun releaseVerifiedDraft(draft: String, evidence: List<Evidence>): ConversationReply {
        val locked = ClaimLock.verify(draft, evidence)
        val onlySupportedFacts = locked.claims.isNotEmpty() && locked.claims.all {
            it.status == ClaimAssessment.Status.SUPPORTED || it.status == ClaimAssessment.Status.PROPOSAL
        }
        val hasUnknown = locked.claims.any { it.status == ClaimAssessment.Status.UNKNOWN }

        val text = if (hasUnknown) {
            locked.answer.replaceFirst(Regex("^UNKNOWN:\\s*", RegexOption.IGNORE_CASE), "")
        } else {
            locked.answer
        }

        return ConversationReply(
            text = text,
            verified = onlySupportedFacts && locked.withheld == 0,
            status = if (onlySupportedFacts && locked.withheld == 0) "VERIFIED" else "ABSTAINED",
        )
    }

    private fun providerFailure(error: String?): ConversationReply = ConversationReply(
        text = error?.let { "Model provider error: $it" } ?: "Model provider request failed.",
        verified = false,
        status = "PROVIDER_ERROR",
    )

    private fun isGenerativeRequest(request: String): Boolean {
        val normalized = request.trim().lowercase()
        val prefixes = listOf(
            "write ", "draft ", "rewrite ", "brainstorm ", "create ", "compose ",
            "outline ", "generate ", "make ", "translate ", "summarize ", "roleplay ",
            "imagine ", "help me write ", "help me draft ", "help me phrase ",
        )
        return prefixes.any(normalized::startsWith)
    }
}

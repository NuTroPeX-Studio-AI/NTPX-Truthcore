package com.ntpx.truthcore.core.agent

import com.ntpx.truthcore.core.evidence.EvidenceSanitizer
import com.ntpx.truthcore.core.model.ModelProvider
import com.ntpx.truthcore.core.model.ModelRequest

data class WorkforceResult(
    val text: String,
    val status: String,
    val stages: List<String> = emptyList(),
)

class MultiAgentWorkforce {
    fun run(goal: String, provider: ModelProvider?): WorkforceResult {
        if (provider == null) {
            return WorkforceResult(
                "A model provider is required for the multi-agent review team.",
                "ABSTAINED",
            )
        }
        val request = goal.trim()
        if (request.isBlank()) return WorkforceResult("A team goal is required.", "ABSTAINED")

        val planner = ask(
            provider,
            "You are the planning specialist in NTPX TruthCore. Produce a concise proposed plan. Do not claim actions ran. Do not invent facts. Mark assumptions explicitly.",
            request,
        ) ?: return WorkforceResult("Planning specialist failed.", "PROVIDER_ERROR")

        val safePlanner = sanitize(planner)
        val critic = ask(
            provider,
            "You are the critic specialist in NTPX TruthCore. Review the supplied proposed plan only. Identify unsupported assumptions, missing dependencies, risks, and ambiguity. Do not add outside facts.",
            "Goal:\n$request\n\nProposed plan:\n$safePlanner",
        ) ?: return WorkforceResult("Critic specialist failed.", "PROVIDER_ERROR", listOf(safePlanner))

        val safeCritic = sanitize(critic)
        val reviewer = ask(
            provider,
            "You are the review specialist in NTPX TruthCore. Synthesize the goal, proposed plan, and critique into a bounded recommendation. Do not claim any action was executed. Clearly separate assumptions from recommendations.",
            "Goal:\n$request\n\nPlan:\n$safePlanner\n\nCritique:\n$safeCritic",
        ) ?: return WorkforceResult("Review specialist failed.", "PROVIDER_ERROR", listOf(safePlanner, safeCritic))

        val safeReview = sanitize(reviewer)
        return WorkforceResult(
            text = safeReview,
            status = "TEAM_GENERATED",
            stages = listOf(safePlanner, safeCritic, safeReview),
        )
    }

    private fun ask(provider: ModelProvider, system: String, prompt: String): String? {
        val result = provider.generate(ModelRequest(systemPrompt = system, userPrompt = prompt, temperature = 0.1))
        return result.text.takeIf { result.success && it.isNotBlank() }
    }

    private fun sanitize(text: String): String {
        val cleaned = EvidenceSanitizer.sanitize(text)
        return if (cleaned.quarantined) "[specialist output quarantined]" else cleaned.text.take(12_000)
    }
}

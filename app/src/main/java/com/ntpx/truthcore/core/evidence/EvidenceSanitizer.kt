package com.ntpx.truthcore.core.evidence

object EvidenceSanitizer {
    private val patterns = linkedMapOf(
        "ignore_instructions" to Regex("\\b(ignore|disregard|forget)\\b.{0,40}\\b(previous|prior|system|developer|instructions?)\\b", RegexOption.IGNORE_CASE),
        "system_prompt_request" to Regex("\\b(system|developer)\\s+prompt\\b", RegexOption.IGNORE_CASE),
        "role_override" to Regex("\\b(you are now|act as|new role|override your role)\\b", RegexOption.IGNORE_CASE),
        "tool_override" to Regex("\\b(call|execute|run|invoke)\\b.{0,40}\\b(tool|shell|command|function)\\b", RegexOption.IGNORE_CASE),
        "secret_exfiltration" to Regex("\\b(reveal|print|show|exfiltrate)\\b.{0,40}\\b(secret|token|password|api key|hidden prompt)\\b", RegexOption.IGNORE_CASE),
    )

    fun sanitize(input: String): SanitizedEvidence {
        val flags = linkedSetOf<String>()
        val safe = input.lineSequence().filter { line ->
            val matches = patterns.filterValues { it.containsMatchIn(line) }.keys
            flags += matches
            matches.isEmpty()
        }.joinToString("\n").trim()
        val quarantine = flags.size >= 2 || (safe.isEmpty() && flags.isNotEmpty())
        return SanitizedEvidence(safe, flags, quarantine)
    }
}

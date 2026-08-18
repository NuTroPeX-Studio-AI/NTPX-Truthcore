package com.ntpx.truthcore.core.truth

import com.ntpx.truthcore.core.evidence.Evidence
import com.ntpx.truthcore.core.evidence.EvidenceSanitizer
import com.ntpx.truthcore.core.semantic.DeepVerificationGate

data class ClaimAssessment(
    val text: String,
    val status: Status,
    val sourceIds: List<String> = emptyList(),
    val reason: String,
) {
    enum class Status { SUPPORTED, UNSUPPORTED, CONTRADICTED, PROPOSAL, UNKNOWN }
}

data class ClaimLockResult(
    val answer: String,
    val claims: List<ClaimAssessment>,
    val released: Int,
    val withheld: Int,
)

object ClaimLock {
    private val citationRegex = Regex("\\[S(\\d+)]")
    private val numberRegex = Regex("\\b\\d+(?:\\.\\d+)?%?\\b")
    private val tokenRegex = Regex("[a-zA-Z0-9_'-]+")
    private val negativeRegex = Regex("\\b(no|not|never|cannot|can't|won't|without|isn't|aren't|doesn't|don't)\\b", RegexOption.IGNORE_CASE)
    private val stop = setOf("the", "a", "an", "and", "or", "is", "are", "was", "were", "to", "of", "in", "on", "at", "for", "with", "from", "that", "this", "it", "be", "as", "by")

    fun verify(
        draft: String,
        evidence: List<Evidence>,
        minSupport: Double = 0.72,
        semanticGate: DeepVerificationGate? = null,
        requireSemantic: Boolean = false,
    ): ClaimLockResult {
        val indexed = evidence.mapIndexed { i, e -> i + 1 to e }.toMap()
        val assessments = mutableListOf<ClaimAssessment>()
        val released = mutableListOf<String>()

        statements(draft).forEach { statement ->
            val lower = statement.lowercase()
            if (lower.startsWith("proposal:")) {
                assessments += ClaimAssessment(statement, ClaimAssessment.Status.PROPOSAL, reason = "Explicit proposal")
                released += statement
                return@forEach
            }
            if (lower.startsWith("unknown:")) {
                assessments += ClaimAssessment(statement, ClaimAssessment.Status.UNKNOWN, reason = "Explicit unknown")
                released += statement
                return@forEach
            }

            val refs = citationRegex.findAll(statement).map { it.groupValues[1].toInt() }.toList()
            val sources = refs.mapNotNull(indexed::get)
            if (refs.isEmpty() || sources.size != refs.size) {
                assessments += ClaimAssessment(statement, ClaimAssessment.Status.UNSUPPORTED, refs.map { "S$it" }, "No valid bound citation")
                return@forEach
            }

            val claim = statement.replace(citationRegex, "").trim()
            var contradicted = false
            var strongest = 0.0
            var reason = "Insufficient support"
            var semanticPassed = false

            for (source in sources) {
                val sanitized = EvidenceSanitizer.sanitize(source.content)
                val effective = if (sanitized.quarantined) 0.0 else source.effectiveStrength()
                if (effective <= 0.0) {
                    reason = "Source expired, quarantined, or untrusted"
                    continue
                }

                val overlap = overlap(claim, sanitized.text)
                val claimNums = numberRegex.findAll(claim).map { it.value }.toSet()
                val sourceNums = numberRegex.findAll(sanitized.text).map { it.value }.toSet()
                if (claimNums.isNotEmpty() && !sourceNums.containsAll(claimNums)) {
                    if (overlap >= 0.60 && sourceNums.isNotEmpty()) contradicted = true
                    reason = "Claim introduces or conflicts with a quantity absent from evidence"
                    continue
                }
                if (overlap >= 0.60 && negativeRegex.containsMatchIn(claim) != negativeRegex.containsMatchIn(sanitized.text)) {
                    contradicted = true
                    reason = "Claim polarity conflicts with evidence"
                    continue
                }

                if (semanticGate != null) {
                    val deep = semanticGate.assess(claim, source)
                    if (deep.semanticAvailable) {
                        if (!deep.releasable) {
                            reason = deep.reason
                            if (deep.reason.contains("contradiction", ignoreCase = true)) contradicted = true
                            continue
                        }
                        semanticPassed = true
                        strongest = maxOf(strongest, effective)
                        reason = deep.reason
                        continue
                    }
                    if (requireSemantic) {
                        reason = deep.reason
                        continue
                    }
                }

                strongest = maxOf(strongest, overlap * effective)
            }

            when {
                contradicted -> assessments += ClaimAssessment(statement, ClaimAssessment.Status.CONTRADICTED, refs.map { "S$it" }, reason)
                strongest >= minSupport -> {
                    val method = if (semanticPassed) "semantic + deterministic" else "deterministic"
                    assessments += ClaimAssessment(statement, ClaimAssessment.Status.SUPPORTED, refs.map { "S$it" }, "Bound evidence passed $method truth checks")
                    released += statement
                }
                else -> assessments += ClaimAssessment(statement, ClaimAssessment.Status.UNSUPPORTED, refs.map { "S$it" }, reason)
            }
        }

        val withheld = assessments.count { it.status == ClaimAssessment.Status.UNSUPPORTED || it.status == ClaimAssessment.Status.CONTRADICTED }
        val answer = if (released.isEmpty()) {
            "I don't have enough verified evidence to answer that reliably."
        } else {
            released.joinToString(" ") + if (withheld > 0) "\n\nSome generated claims were withheld because ClaimLock could not verify them." else ""
        }
        return ClaimLockResult(answer, assessments, released.size, withheld)
    }

    private fun statements(text: String): List<String> = text.trim()
        .split(Regex("(?<=[.!?])\\s+|\\n+"))
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun overlap(claim: String, source: String): Double {
        val c = tokenRegex.findAll(claim.lowercase()).map { it.value }.filterNot(stop::contains).toSet()
        if (c.isEmpty()) return 1.0
        val s = tokenRegex.findAll(source.lowercase()).map { it.value }.filterNot(stop::contains).toSet()
        return c.intersect(s).size.toDouble() / c.size
    }
}

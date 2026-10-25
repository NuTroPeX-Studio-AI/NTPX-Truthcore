package com.ntpx.truthcore.core.truth

import com.ntpx.truthcore.core.evidence.Evidence
import com.ntpx.truthcore.core.evidence.EvidenceSanitizer
import com.ntpx.truthcore.core.semantic.DeepVerificationGate

data class ClaimAssessment(
    val text: String,
    val status: Status,
    val sourceIds: List<String> = emptyList(),
    val reason: String,
    val method: String? = null,
    /** Cited sources that individually passed the deterministic gate (the only ones the semantic gate judges). */
    val supportingIds: List<String> = emptyList(),
) {
    enum class Status { SUPPORTED, UNSUPPORTED, CONTRADICTED, PROPOSAL, UNKNOWN }
}

data class ClaimLockResult(
    val answer: String,
    val claims: List<ClaimAssessment>,
    val released: Int,
    val withheld: Int,
)

/**
 * Deterministic factual release gate.
 *
 * A factual statement is released only when it carries valid `[S#]` citations
 * whose sanitized, unexpired sources overlap the claim, contain every quantity
 * the claim states, and do not flip its polarity. An optional semantic/NLI gate
 * is strictly additive: it can withhold or contradict a claim the deterministic
 * gate supported, but it never releases a claim the deterministic gate rejected.
 */
object ClaimLock {
    const val CONTRADICTION_OVERLAP = 0.60

    private val citationRegex = Regex("\\[S(\\d+)]")
    private val numberRegex = Regex("(?<![\\w.])(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?%?(?![\\w])")
    private val tokenRegex = Regex("[a-zA-Z0-9_'-]+")
    private val negativeRegex = Regex(
        "\\b(no|not|never|none|cannot|can't|won't|without|isn't|aren't|wasn't|weren't|doesn't|don't|didn't|hasn't|haven't|neither|nor)\\b",
        RegexOption.IGNORE_CASE,
    )
    /** Whitespace followed by an uppercase letter: an abbreviation before it may end a sentence. */
    private val sentenceStart = Regex("^\\s+\\p{Lu}")
    private val citationOnly = Regex("^(\\[S\\d+]\\s*[,;]?\\s*)+[.!?]?$")
    private val stop = setOf("the", "a", "an", "and", "or", "is", "are", "was", "were", "to", "of", "in", "on", "at", "for", "with", "from", "that", "this", "it", "be", "as", "by")
    private val abbreviations = listOf("e.g.", "i.e.", "etc.", "vs.", "mr.", "mrs.", "ms.", "dr.", "prof.", "inc.", "ltd.", "co.", "no.", "fig.", "approx.", "st.", "jr.", "sr.", "u.s.", "u.k.")

    fun verify(
        draft: String,
        evidence: List<Evidence>,
        minSupport: Double = 0.72,
        semanticGate: DeepVerificationGate? = null,
        requireSemantic: Boolean = false,
    ): ClaimLockResult {
        val indexed = evidence.mapIndexed { i, e -> i + 1 to e }.toMap()
        val assessments = statements(draft).map { statement ->
            val deterministic = assessDeterministic(statement, indexed, minSupport)
            if (deterministic.status == ClaimAssessment.Status.SUPPORTED && (semanticGate != null || requireSemantic)) {
                applySemanticGate(deterministic, indexed, semanticGate, requireSemantic)
            } else {
                deterministic
            }
        }
        return summarize(assessments)
    }

    private fun assessDeterministic(statement: String, indexed: Map<Int, Evidence>, minSupport: Double): ClaimAssessment {
        val lower = statement.lowercase()
        if (lower.startsWith("proposal:")) return ClaimAssessment(statement, ClaimAssessment.Status.PROPOSAL, reason = "Explicit proposal")
        if (lower.startsWith("unknown:")) return ClaimAssessment(statement, ClaimAssessment.Status.UNKNOWN, reason = "Explicit unknown")

        val refs = citationRegex.findAll(statement).map { it.groupValues[1].toInt() }.distinct().toList()
        val sourceIds = refs.map { "S$it" }
        val sources = refs.mapNotNull(indexed::get)
        if (refs.isEmpty() || sources.size != refs.size) {
            return ClaimAssessment(statement, ClaimAssessment.Status.UNSUPPORTED, sourceIds, "No valid bound citation")
        }

        val claim = statement.replace(citationRegex, "").trim()
        val claimTokens = tokens(claim)
        if (claimTokens.isEmpty()) {
            return ClaimAssessment(statement, ClaimAssessment.Status.UNSUPPORTED, sourceIds, "Claim has no checkable content")
        }
        val claimNums = numbers(claim)
        val claimNegative = negativeRegex.containsMatchIn(claim)

        var contradicted = false
        var strongest = 0.0
        var reason = "Insufficient support"
        val supportingIds = mutableListOf<String>()

        sources.forEachIndexed { position, source ->
            val sanitized = EvidenceSanitizer.sanitize(source.content)
            val effective = if (sanitized.quarantined) 0.0 else source.effectiveStrength()
            if (effective <= 0.0 || sanitized.text.isBlank()) {
                reason = "Source expired, quarantined, or untrusted"
                return@forEachIndexed
            }
            val wholeOverlap = overlap(claimTokens, sanitized.text)
            val best = bestSentences(claimTokens, sanitized.text)
            val sourceNums = numbers(sanitized.text)

            if (claimNums.isNotEmpty() && !sourceNums.containsAll(claimNums)) {
                if (best.second >= CONTRADICTION_OVERLAP && best.first.any { numbers(it).isNotEmpty() }) contradicted = true
                reason = "Claim introduces or conflicts with a quantity absent from evidence"
                return@forEachIndexed
            }
            // Every sentence tied for the best match is checked, so sentence order cannot hide a contradiction.
            if (best.second >= CONTRADICTION_OVERLAP && best.first.any { claimNegative != negativeRegex.containsMatchIn(it) }) {
                contradicted = true
                reason = "Claim polarity conflicts with evidence"
                return@forEachIndexed
            }
            val support = wholeOverlap * effective
            if (support >= minSupport) supportingIds += sourceIds[position]
            strongest = maxOf(strongest, support)
        }

        return when {
            contradicted -> ClaimAssessment(statement, ClaimAssessment.Status.CONTRADICTED, sourceIds, reason)
            strongest >= minSupport -> ClaimAssessment(
                statement, ClaimAssessment.Status.SUPPORTED, sourceIds,
                "Bound evidence passed deterministic truth checks", method = "deterministic",
                supportingIds = supportingIds,
            )
            else -> ClaimAssessment(statement, ClaimAssessment.Status.UNSUPPORTED, sourceIds, reason)
        }
    }

    private fun applySemanticGate(
        assessment: ClaimAssessment,
        indexed: Map<Int, Evidence>,
        gate: DeepVerificationGate?,
        requireSemantic: Boolean,
    ): ClaimAssessment {
        if (gate == null) {
            return assessment.copy(status = ClaimAssessment.Status.UNSUPPORTED, reason = "Semantic verification required but no gate is configured")
        }
        val claim = assessment.text.replace(citationRegex, "").trim()
        // Only individually supporting sources are judged, so an unrelated co-citation can
        // neither unlock nor stand in for a completed semantic verdict.
        val sources = assessment.supportingIds.ifEmpty { assessment.sourceIds }
            .mapNotNull { indexed[it.removePrefix("S").toInt()] }
        var passed = false
        var contradicted = false
        var completed = 0
        var unavailable: String? = null
        var lastReason = "Semantic entailment is below release threshold"
        for (source in sources) {
            val deep = gate.assess(claim, source)
            if (!deep.semanticAvailable) {
                unavailable = deep.reason
                continue
            }
            completed += 1
            lastReason = deep.reason
            if (deep.reason.contains("contradiction", ignoreCase = true)) contradicted = true
            if (deep.releasable) passed = true
        }
        return when {
            contradicted -> assessment.copy(
                status = ClaimAssessment.Status.CONTRADICTED,
                reason = "Semantic verifier detected contradiction with cited evidence",
            )
            passed -> assessment.copy(reason = "Bound evidence passed semantic + deterministic truth checks", method = "semantic+deterministic")
            completed == 0 && unavailable != null && !requireSemantic ->
                assessment.copy(reason = "${assessment.reason} (semantic gate unavailable: $unavailable)")
            completed == 0 && unavailable != null -> assessment.copy(status = ClaimAssessment.Status.UNSUPPORTED, reason = unavailable)
            else -> assessment.copy(status = ClaimAssessment.Status.UNSUPPORTED, reason = lastReason)
        }
    }

    private fun summarize(assessments: List<ClaimAssessment>): ClaimLockResult {
        val released = assessments.filter {
            it.status == ClaimAssessment.Status.SUPPORTED || it.status == ClaimAssessment.Status.PROPOSAL || it.status == ClaimAssessment.Status.UNKNOWN
        }.map { it.text }
        val withheld = assessments.count { it.status == ClaimAssessment.Status.UNSUPPORTED || it.status == ClaimAssessment.Status.CONTRADICTED }
        val answer = if (released.isEmpty()) {
            "I don't have enough verified evidence to answer that reliably."
        } else {
            released.joinToString(" ") + if (withheld > 0) "\n\nSome generated claims were withheld because ClaimLock could not verify them." else ""
        }
        return ClaimLockResult(answer, assessments, released.size, withheld)
    }

    /** Sentence splitter that keeps abbreviations intact and re-attaches orphan citations. */
    fun statements(text: String): List<String> {
        var working = text.trim()
        val placeholders = mutableListOf<String>()
        abbreviations.forEach { abbr ->
            // Only mask mid-sentence uses; before whitespace + uppercase (or end of text) the
            // abbreviation may end a sentence, so the boundary is kept and each sentence is checked alone.
            val source = working
            working = Regex("\\b" + Regex.escape(abbr), RegexOption.IGNORE_CASE).replace(source) { match ->
                val rest = source.substring(match.range.last + 1)
                if (rest.isBlank() || sentenceStart.containsMatchIn(rest)) {
                    match.value
                } else {
                    placeholders += match.value
                    "\u0000${placeholders.size - 1}\u0000"
                }
            }
        }
        val restore = Regex("\u0000(\\d+)\u0000")
        val raw = working.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { part -> restore.replace(part) { placeholders[it.groupValues[1].toInt()] }.trim() }
            .map { it.replace(Regex("^[-*•]\\s+"), "").trim() }
            .filter(String::isNotBlank)

        val merged = mutableListOf<String>()
        raw.forEach { item ->
            if (citationOnly.matches(item) && merged.isNotEmpty()) {
                merged[merged.lastIndex] = merged.last() + " " + item
            } else {
                merged += item
            }
        }
        return merged
    }

    private fun bestSentences(claimTokens: Set<String>, source: String): Pair<List<String>, Double> {
        var texts = mutableListOf(source)
        var bestScore = 0.0
        statements(source).forEach { sentence ->
            val score = overlap(claimTokens, sentence)
            if (score > bestScore) {
                bestScore = score
                texts = mutableListOf(sentence)
            } else if (score == bestScore && score > 0.0) {
                texts += sentence
            }
        }
        return texts to bestScore
    }

    private val thousands = Regex("(?<=\\d),(?=\\d{3}\\b)")

    private fun tokens(value: String): Set<String> =
        tokenRegex.findAll(thousands.replace(value.lowercase(), "")).map { it.value }.filterNot(stop::contains).toSet()

    private fun overlap(claimTokens: Set<String>, source: String): Double {
        if (claimTokens.isEmpty()) return 0.0
        return claimTokens.intersect(tokens(source)).size.toDouble() / claimTokens.size
    }

    private fun numbers(value: String): Set<String> =
        numberRegex.findAll(value).map { it.value.replace(",", "") }.toSet()
}

package com.ntpx.truthcore.core.semantic

import java.time.Instant

data class TemporalFact(
    val id: String,
    val subject: String,
    val predicate: String,
    val value: String,
    val validFrom: Instant = Instant.MIN,
    val validUntil: Instant? = null,
    val sourceId: String,
    val trust: Double,
) {
    fun active(at: Instant): Boolean = !at.isBefore(validFrom) && (validUntil == null || at.isBefore(validUntil))
}

data class TemporalResolution(
    val selected: TemporalFact?,
    val conflicts: List<TemporalFact>,
)

class TemporalKnowledgeGraph {
    private val facts = mutableListOf<TemporalFact>()

    fun add(fact: TemporalFact) {
        require(fact.trust in 0.0..1.0)
        facts += fact
    }

    fun resolve(subject: String, predicate: String, at: Instant = Instant.now()): TemporalResolution {
        val active = facts.filter { it.subject == subject && it.predicate == predicate && it.active(at) }
        if (active.isEmpty()) return TemporalResolution(null, emptyList())
        val selected = active.maxWithOrNull(compareBy<TemporalFact> { it.trust }.thenBy { it.validFrom })
        val conflicts = active.filter { it.value != selected?.value }
        return TemporalResolution(selected, conflicts)
    }
}

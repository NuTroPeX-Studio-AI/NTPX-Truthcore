package com.ntpx.truthcore.core.rag

import com.ntpx.truthcore.core.data.TruthCoreStore
import com.ntpx.truthcore.core.evidence.Evidence

class EvidenceRetriever(private val store: TruthCoreStore) {
    fun retrieve(query: String, base: List<Evidence> = emptyList(), limit: Int = 16): List<Evidence> {
        val knowledge = store.searchKnowledge(query, limit)
        val memories = store.searchMemory(query, limit = 8).map {
            Evidence(
                id = "memory:${it.id}",
                label = "Saved user memory",
                content = "Saved user memory: ${it.content}",
                trust = it.trust.coerceAtMost(0.90),
                sourceUri = "memory://${it.id}",
                independentKey = "memory:${it.id}",
                expiresAt = it.expiresAt,
            )
        }
        return (base + knowledge + memories)
            .filter { it.effectiveStrength() > 0.0 }
            .distinctBy { it.independentKey }
            .sortedByDescending { it.effectiveStrength() }
            .take(limit)
    }
}

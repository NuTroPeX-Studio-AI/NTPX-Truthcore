package com.ntpx.truthcore.core.memory

import java.time.Instant

enum class MemoryKind { EPISODIC, SEMANTIC, PROCEDURAL }

data class MemoryRecord(
    val id: String,
    val kind: MemoryKind,
    val content: String,
    val trust: Double = 0.85,
    val importance: Double = 0.5,
    val createdAt: Instant = Instant.now(),
    val expiresAt: Instant? = null,
) {
    fun isUsable(now: Instant = Instant.now()): Boolean = trust > 0.0 && (expiresAt == null || expiresAt.isAfter(now))
}

class MemoryEngine {
    private val records = linkedMapOf<String, MemoryRecord>()

    fun remember(record: MemoryRecord) {
        require(record.trust in 0.0..1.0)
        require(record.importance in 0.0..1.0)
        records[record.id] = record
    }

    fun search(query: String, limit: Int = 8): List<MemoryRecord> {
        val terms = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        return records.values.asSequence()
            .filter { it.isUsable() }
            .map { record -> record to terms.count { t -> record.content.lowercase().contains(t) } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MemoryRecord, Int>> { it.second }.thenByDescending { it.first.importance })
            .take(limit)
            .map { it.first }
            .toList()
    }
}

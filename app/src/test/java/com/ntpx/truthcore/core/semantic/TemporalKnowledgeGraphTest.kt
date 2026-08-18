package com.ntpx.truthcore.core.semantic

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TemporalKnowledgeGraphTest {
    @Test fun resolvesHighestTrustActiveFactAndSurfacesConflict() {
        val graph = TemporalKnowledgeGraph()
        graph.add(TemporalFact("old", "project", "platform", "web", sourceId = "s1", trust = 0.7))
        graph.add(TemporalFact("new", "project", "platform", "android", validFrom = Instant.parse("2026-01-01T00:00:00Z"), sourceId = "s2", trust = 0.95))
        val result = graph.resolve("project", "platform", Instant.parse("2026-08-18T00:00:00Z"))
        assertEquals("android", result.selected?.value)
        assertEquals(1, result.conflicts.size)
        assertEquals("web", result.conflicts.first().value)
    }

    @Test fun expiredFactIsExcluded() {
        val graph = TemporalKnowledgeGraph()
        graph.add(TemporalFact("expired", "x", "state", "old", validUntil = Instant.parse("2025-01-01T00:00:00Z"), sourceId = "s1", trust = 1.0))
        val result = graph.resolve("x", "state", Instant.parse("2026-08-18T00:00:00Z"))
        assertNull(result.selected)
    }
}

package com.ntpx.truthcore.core.semantic

import org.junit.Assert.*
import org.junit.Test

class ContradictionResolverTest {
    @Test fun closeTrustConflictRemainsUnresolved() {
        val selected = TemporalFact("a", "x", "state", "one", sourceId = "s1", trust = 0.90)
        val conflict = TemporalFact("b", "x", "state", "two", sourceId = "s2", trust = 0.82)
        val result = ContradictionResolver.resolve(TemporalResolution(selected, listOf(conflict)), trustMargin = 0.15)
        assertFalse(result.resolved)
        assertEquals(2, result.unresolved.size)
    }

    @Test fun clearTrustMarginCanResolve() {
        val selected = TemporalFact("a", "x", "state", "one", sourceId = "s1", trust = 0.95)
        val conflict = TemporalFact("b", "x", "state", "two", sourceId = "s2", trust = 0.60)
        val result = ContradictionResolver.resolve(TemporalResolution(selected, listOf(conflict)), trustMargin = 0.15)
        assertTrue(result.resolved)
        assertEquals("one", result.selected?.value)
    }
}

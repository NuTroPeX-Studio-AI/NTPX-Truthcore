package com.ntpx.truthcore.core.memory

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MemoryEngineTest {
    @Test fun expiredMemoryIsNotReturned() {
        val engine = MemoryEngine()
        engine.remember(MemoryRecord("1", MemoryKind.SEMANTIC, "Project Aurora is local-first", expiresAt = Instant.EPOCH))
        assertTrue(engine.search("Aurora local").isEmpty())
    }

    @Test fun trustedMemoryCanBeFound() {
        val engine = MemoryEngine()
        engine.remember(MemoryRecord("1", MemoryKind.SEMANTIC, "TruthCore is Android-first"))
        assertEquals("1", engine.search("TruthCore Android").first().id)
    }
}

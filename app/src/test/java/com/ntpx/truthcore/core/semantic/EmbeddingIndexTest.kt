package com.ntpx.truthcore.core.semantic

import org.junit.Assert.*
import org.junit.Test

class EmbeddingIndexTest {
    @Test fun absentProviderFailsClosed() {
        val index = EmbeddingIndex()
        assertFalse(index.add("a", "hello"))
        assertTrue(index.search("hello").isEmpty())
    }

    @Test fun semanticSearchUsesProviderVectors() {
        val provider = EmbeddingProvider { text ->
            val vector = if (text.contains("android", ignoreCase = true)) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
            EmbeddingVector(true, vector, provider = "fixture", reason = "test")
        }
        val index = EmbeddingIndex(provider)
        assertTrue(index.add("android", "Android native runtime"))
        assertTrue(index.add("other", "Unrelated topic"))
        assertEquals("android", index.search("Android app").first().id)
    }
}

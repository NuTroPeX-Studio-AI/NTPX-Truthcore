package com.ntpx.truthcore.core.semantic

import com.ntpx.truthcore.core.evidence.Evidence
import org.junit.Assert.*
import org.junit.Test

class SourceAuthenticatorTest {
    @Test fun rejectsHashMismatch() {
        val evidence = Evidence("1", "source", "verified content", sourceUri = "https://example.com/data")
        val result = SourceAuthenticator.verify(evidence, expectedHash = "wrong")
        assertFalse(result.authenticated)
    }

    @Test fun rejectsNonHttpsRemoteSource() {
        val evidence = Evidence("1", "source", "content", sourceUri = "http://example.com/data")
        assertFalse(SourceAuthenticator.verify(evidence).authenticated)
    }

    @Test fun acceptsStructurallyAuthenticatedHttpsSource() {
        val evidence = Evidence("1", "source", "content", sourceUri = "https://example.com/data")
        assertTrue(SourceAuthenticator.verify(evidence, evidence.contentHash).authenticated)
    }
}

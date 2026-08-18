package com.ntpx.truthcore.core.truth

import com.ntpx.truthcore.core.evidence.Evidence
import org.junit.Assert.*
import org.junit.Test

class ClaimLockTest {
    @Test fun supportedClaimPasses() {
        val e = Evidence("a", "source", "Paris is the capital of France.")
        val r = ClaimLock.verify("Paris is the capital of France [S1].", listOf(e), 0.7)
        assertEquals(1, r.released)
        assertEquals(0, r.withheld)
    }

    @Test fun missingCitationFailsClosed() {
        val e = Evidence("a", "source", "Paris is the capital of France.")
        val r = ClaimLock.verify("Paris is the capital of France.", listOf(e), 0.7)
        assertEquals(0, r.released)
        assertTrue(r.answer.startsWith("I don't have enough verified evidence"))
    }

    @Test fun conflictingNumberIsWithheld() {
        val e = Evidence("a", "source", "The device has 16 GB of memory.")
        val r = ClaimLock.verify("The device has 32 GB of memory [S1].", listOf(e), 0.7)
        assertEquals(0, r.released)
        assertEquals(1, r.withheld)
    }
}

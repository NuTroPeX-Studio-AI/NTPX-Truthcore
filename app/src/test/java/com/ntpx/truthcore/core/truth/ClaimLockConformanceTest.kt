package com.ntpx.truthcore.core.truth

import com.ntpx.truthcore.core.evidence.Evidence
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** Runs the shared conformance fixtures that web/tests/conformance.test.mjs also runs. */
class ClaimLockConformanceTest {
    private fun fixtureFile(): File =
        listOf(File("../conformance/claimlock-cases.json"), File("conformance/claimlock-cases.json")).first { it.exists() }

    @Test fun androidMatchesSharedClaimLockFixtures() {
        val root = JSONObject(fixtureFile().readText())
        val minSupport = root.getDouble("minSupport")
        val cases = root.getJSONArray("cases")
        assertTrue(cases.length() > 0)
        val failures = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val evidenceJson = case.getJSONArray("evidence")
            val evidence = (0 until evidenceJson.length()).map { j ->
                val item = evidenceJson.getJSONObject(j)
                Evidence(
                    id = "e${j + 1}",
                    label = "fixture",
                    content = item.getString("content"),
                    trust = item.optDouble("trust", 1.0),
                    expiresAt = item.optString("expiresAt").takeIf { it.isNotBlank() }?.let(Instant::parse),
                )
            }
            val expected = case.getJSONArray("expect").let { arr -> (0 until arr.length()).map { arr.getString(it) } }
            val actual = ClaimLock.verify(case.getString("draft"), evidence, minSupport).claims.map { it.status.name }
            if (expected != actual) failures += "${case.getString("name")}: expected $expected but was $actual"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }
}

package com.ntpx.truthcore.core.agent

import com.ntpx.truthcore.core.mcp.McpClient
import com.ntpx.truthcore.core.mcp.McpConfig
import com.ntpx.truthcore.core.model.ModelProvider
import com.ntpx.truthcore.core.model.ModelRequest
import com.ntpx.truthcore.core.model.ModelResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlatformTest {
    @Test fun mcpRejectsCleartextAndEmbeddedCredentials() {
        assertNotNull(McpClient(McpConfig("http://example.com/mcp")).validate())
        assertNotNull(McpClient(McpConfig("https://user:pass@example.com/mcp")).validate())
        assertEquals(null, McpClient(McpConfig("https://example.com/mcp")).validate())
        assertEquals("2026-07-28", McpClient.PROTOCOL_VERSION)
    }

    @Test fun multiAgentWorkforceIsAdvisoryAndGenerated() {
        var calls = 0
        val provider = object : ModelProvider {
            override val displayName = "fixture"
            override fun generate(request: ModelRequest): ModelResponse {
                calls += 1
                return ModelResponse(true, text = when (calls) {
                    1 -> "PLAN: inspect requirements and identify assumptions."
                    2 -> "CRITIQUE: the plan needs explicit validation boundaries."
                    else -> "REVIEW: proceed with bounded implementation and verification."
                })
            }
        }
        val result = MultiAgentWorkforce().run("Finish a bounded feature", provider)
        assertEquals("TEAM_GENERATED", result.status)
        assertEquals(3, result.stages.size)
        assertTrue(result.text.contains("REVIEW"))
        assertEquals(3, calls)
    }

    @Test fun workforceFailsClosedWithoutProvider() {
        val result = MultiAgentWorkforce().run("goal", null)
        assertEquals("ABSTAINED", result.status)
    }
}

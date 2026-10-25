package com.ntpx.truthcore.core.data

import com.ntpx.truthcore.core.TruthCoreRuntime
import com.ntpx.truthcore.core.agent.ToolCall
import com.ntpx.truthcore.core.agent.ToolRuntime
import com.ntpx.truthcore.core.evidence.Evidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryAndToolsTest {
    @Test fun approvalCodesAreSpeakableSingleUseAndActionBound() {
        val repo = InMemoryTruthCoreRepository()
        val code = repo.issueApproval("memory.remember|content=x")
        assertTrue(code.matches(Regex("\\d{6}")))
        assertFalse(repo.consumeApproval(code, "memory.remember|content=y"))
        assertTrue(repo.consumeApproval(code, "memory.remember|content=x"))
        assertFalse(repo.consumeApproval(code, "memory.remember|content=x"))
    }

    @Test fun expiredApprovalIsRejected() {
        val repo = InMemoryTruthCoreRepository()
        val code = repo.issueApproval("action", ttlSeconds = 0)
        assertFalse(repo.consumeApproval(code, "action"))
    }

    @Test fun auditChainDetectsTampering() {
        val repo = InMemoryTruthCoreRepository()
        repo.appendAudit("one", "a")
        repo.appendAudit("two", "b")
        repo.appendAudit("three", "c")
        assertTrue(repo.verifyAuditChain())
        repo.tamperAudit(1)
        assertFalse(repo.verifyAuditChain())
    }

    @Test fun forgetRequiresEveryQueryWord() {
        val repo = InMemoryTruthCoreRepository()
        repo.remember("Project Orion launches in spring")
        repo.remember("Orion is also a constellation")
        assertEquals(0, repo.forgetMemory("orion launches autumn"))
        assertEquals(1, repo.forgetMemory("orion launches"))
        assertEquals(1, repo.memoryCount())
    }

    @Test fun forgetKeepsShortWordsAndMatchesWholeWords() {
        val repo = InMemoryTruthCoreRepository()
        repo.remember("Notes on Project X launch")
        repo.remember("Notes on Project Y launch")
        repo.remember("Project Xenon roadmap")
        assertEquals(1, repo.forgetMemory("project X"))
        assertEquals(2, repo.memoryCount())
        assertTrue(repo.searchMemory("project").none { it.content.contains("Project X ") })
    }

    @Test fun writeToolsRequireApprovalAndExecuteOnce() {
        val repo = InMemoryTruthCoreRepository()
        val tools = ToolRuntime(repo)
        val proposed = tools.execute(ToolCall("memory.remember", mapOf("content" to "Codename is Orion")))
        assertTrue(proposed.approvalRequired)
        assertEquals(0, repo.memoryCount())
        val code = assertNotNullCode(proposed.approvalToken)

        val executed = tools.approve("${code.substring(0, 3)} ${code.substring(3)}")
        assertTrue(executed.success)
        assertEquals(1, repo.memoryCount())
        assertFalse(tools.approve(code).success)
        assertTrue(repo.verifyAuditChain())
    }

    @Test fun readOnlyToolsRunWithoutApproval() {
        val repo = InMemoryTruthCoreRepository()
        repo.remember("The wifi password hint is the dog's name")
        val result = ToolRuntime(repo).execute(ToolCall("memory.search", mapOf("query" to "wifi hint")))
        assertTrue(result.success)
        assertTrue(result.output.contains("wifi"))
    }

    @Test fun cancelDropsPendingApprovals() {
        val tools = ToolRuntime(InMemoryTruthCoreRepository())
        val proposed = tools.execute(ToolCall("memory.remember", mapOf("content" to "x")))
        assertEquals(1, tools.cancelPending())
        assertFalse(tools.approve(proposed.approvalToken!!).success)
    }

    @Test fun mcpCallWithoutServerFailsAfterApproval() {
        val tools = ToolRuntime(InMemoryTruthCoreRepository())
        val proposed = tools.execute(ToolCall("mcp.call", mapOf("tool" to "search", "arguments_json" to "{}")))
        assertTrue(proposed.approvalRequired)
        val result = tools.approve(proposed.approvalToken!!)
        assertFalse(result.success)
        assertTrue(result.output.contains("No MCP server"))
    }

    @Test fun runtimeRoutesForgetKnowledgeAndDeny() {
        val repo = InMemoryTruthCoreRepository()
        repo.upsertKnowledge(Evidence("k1", "Router", "The router admin page is on port 8443", trust = 0.8))
        val runtime = TruthCoreRuntime(repo)

        val forget = runtime.respond("forget knowledge router admin", null)
        assertEquals("APPROVAL_REQUIRED", forget.status)
        assertTrue(forget.text.contains("knowledge.forget"))

        val deny = runtime.respond("deny", null)
        assertEquals("ACTION_DENIED", deny.status)
        assertTrue(deny.text.contains("Cancelled 1"))
        assertEquals(1, repo.searchKnowledge("router").size)
    }

    @Test fun runtimeApprovalFlowPersistsMemory() {
        val repo = InMemoryTruthCoreRepository()
        val runtime = TruthCoreRuntime(repo)
        val proposed = runtime.respond("remember that my locker is 42", null)
        val code = Regex("approve (\\d{6})").find(proposed.text)!!.groupValues[1]
        val approved = runtime.respond("approve $code", null)
        assertEquals("ACTION_EXECUTED", approved.status)
        assertTrue(runtime.respond("what do you remember about locker", null).text.contains("42"))
    }

    private fun assertNotNullCode(code: String?): String {
        assertNotNull(code)
        return code!!
    }
}

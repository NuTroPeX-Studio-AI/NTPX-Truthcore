package com.ntpx.truthcore.core

import android.content.Context
import com.ntpx.truthcore.core.agent.AgentExecutive
import com.ntpx.truthcore.core.agent.MultiAgentWorkforce
import com.ntpx.truthcore.core.agent.ToolCall
import com.ntpx.truthcore.core.agent.ToolRuntime
import com.ntpx.truthcore.core.chat.ConversationEngine
import com.ntpx.truthcore.core.chat.ConversationReply
import com.ntpx.truthcore.core.chat.VerificationOptions
import com.ntpx.truthcore.core.data.TruthCoreRepository
import com.ntpx.truthcore.core.data.TruthCoreStore
import com.ntpx.truthcore.core.evidence.EvidenceSanitizer
import com.ntpx.truthcore.core.mcp.McpSession
import com.ntpx.truthcore.core.model.ModelProvider
import com.ntpx.truthcore.core.model.VerificationSession
import com.ntpx.truthcore.core.rag.EvidenceRetriever

/**
 * Process-wide TruthCore runtime. The activity and the hands-free service share
 * one instance (via [get]) so they see the same database connection, the same
 * pending approvals, and one unforked audit chain.
 */
class TruthCoreRuntime internal constructor(private val store: TruthCoreRepository) {
    private val retriever = EvidenceRetriever(store)
    private val conversation = ConversationEngine(
        evidenceResolver = { query -> retriever.retrieve(query) },
        verification = {
            VerificationOptions(
                semanticGate = VerificationSession.semanticGate,
                requireSemantic = VerificationSession.requireSemantic,
                embeddings = VerificationSession.embeddings,
            )
        },
    )
    private val tools = ToolRuntime(store)
    private val executive = AgentExecutive(tools)
    private val workforce = MultiAgentWorkforce()

    fun respond(input: String, provider: ModelProvider?): ConversationReply {
        val request = input.trim()
        val lower = request.lowercase()

        if (lower.startsWith("approve ")) {
            val result = executive.run(request, provider)
            return ConversationReply(result.text, result.status == "ACTION_EXECUTED", result.status)
        }

        if (lower in setOf("deny", "deny all", "cancel", "cancel approvals", "reject")) {
            val count = tools.cancelPending()
            store.appendAudit("approval.cancelled", count.toString())
            return ConversationReply(
                if (count == 0) "There are no pending approvals." else "Cancelled $count pending approval${if (count == 1) "" else "s"}.",
                verified = true,
                status = "ACTION_DENIED",
            )
        }

        if (lower.startsWith("remove knowledge ") || lower.startsWith("forget knowledge ")) {
            val query = request.substringAfter("knowledge ").removePrefix("about ").trim()
            val result = tools.execute(ToolCall("knowledge.forget", mapOf("query" to query)))
            return ConversationReply(result.output, verified = false, status = if (result.approvalRequired) "APPROVAL_REQUIRED" else "ACTION_FAILED")
        }

        if (lower.startsWith("forget ")) {
            val query = request.substringAfter(' ').removePrefix("about ").removePrefix("that ").trim()
            val result = tools.execute(ToolCall("memory.forget", mapOf("query" to query)))
            return ConversationReply(result.output, verified = false, status = if (result.approvalRequired) "APPROVAL_REQUIRED" else "ACTION_FAILED")
        }

        if (lower == "memory count" || lower == "how many memories") {
            val count = store.memoryCount()
            return ConversationReply("$count saved memor${if (count == 1) "y" else "ies"} on this device.", verified = true, status = "LOCAL")
        }

        if (lower.startsWith("remember that ")) {
            val content = request.substringAfter("remember that ", "").trim()
            val result = tools.execute(ToolCall("memory.remember", mapOf("content" to content)))
            return ConversationReply(result.output, verified = false, status = if (result.approvalRequired) "APPROVAL_REQUIRED" else "ACTION_FAILED")
        }

        if (lower.startsWith("what do you remember about ") || lower.startsWith("search memory for ")) {
            val query = request.substringAfter("about ", request.substringAfter("for ", "")).trim()
            val result = tools.execute(ToolCall("memory.search", mapOf("query" to query)))
            return ConversationReply(result.output, verified = result.success, status = if (result.success) "LOCAL" else "ACTION_FAILED")
        }

        if (lower.startsWith("search knowledge for ")) {
            val query = request.substringAfter("search knowledge for ", "").trim()
            val result = tools.execute(ToolCall("knowledge.search", mapOf("query" to query)))
            return ConversationReply(result.output, verified = result.success, status = if (result.success) "LOCAL" else "ACTION_FAILED")
        }

        if (lower.startsWith("add knowledge: ")) {
            val body = request.substringAfter(':').trim()
            val label = body.substringBefore('|').trim()
            val content = body.substringAfter('|', "").trim()
            val result = tools.execute(ToolCall("knowledge.add", mapOf("label" to label, "content" to content)))
            return ConversationReply(result.output, verified = false, status = if (result.approvalRequired) "APPROVAL_REQUIRED" else "ACTION_FAILED")
        }

        if (lower == "audit status" || lower == "verify audit") {
            val valid = store.verifyAuditChain()
            return ConversationReply(
                if (valid) "The local TruthCore audit hash chain is internally consistent." else "The local TruthCore audit hash chain failed verification.",
                verified = valid,
                status = if (valid) "LOCAL" else "ALERT",
            )
        }

        if (lower == "list tools" || lower == "tools") {
            return ConversationReply(tools.descriptions(), verified = true, status = "LOCAL")
        }

        if (lower == "mcp status") {
            return ConversationReply(
                if (McpSession.client != null) "An MCP server is connected for this app process." else "No MCP server is connected.",
                verified = true,
                status = "LOCAL",
            )
        }

        if (lower == "mcp list" || lower == "list mcp tools") {
            val client = McpSession.client
                ?: return ConversationReply("No MCP server is connected. Open MCP settings first.", false, "ABSTAINED")
            val result = client.listTools()
            return result.fold(
                onSuccess = { items ->
                    val listing = items.joinToString("\n") { "- ${it.name}: ${it.description.take(200)}" }
                    val sanitized = EvidenceSanitizer.sanitize(listing)
                    ConversationReply(
                        (if (sanitized.quarantined) "[MCP tool listing quarantined: ${sanitized.flags.joinToString()}]" else sanitized.text)
                            .ifBlank { "The MCP server returned no tools." },
                        verified = false,
                        status = "EXTERNAL_DATA",
                    )
                },
                onFailure = { ConversationReply("MCP tools/list failed: ${it.message ?: "unknown error"}", false, "ACTION_FAILED") },
            )
        }

        if (lower.startsWith("mcp call ")) {
            val body = request.substringAfter("mcp call ").trim()
            val toolName = body.substringBefore(' ').trim()
            val argumentsJson = body.substringAfter(' ', "{}").trim().ifBlank { "{}" }
            val result = tools.execute(
                ToolCall("mcp.call", mapOf("tool" to toolName, "arguments_json" to argumentsJson))
            )
            return ConversationReply(
                result.output,
                verified = false,
                status = when {
                    result.approvalRequired -> "APPROVAL_REQUIRED"
                    result.success -> "ACTION_EXECUTED"
                    else -> "ACTION_FAILED"
                },
            )
        }

        if (lower.startsWith("team:") || lower.startsWith("/team ") || lower.startsWith("review team: ")) {
            val goal = when {
                lower.startsWith("team:") -> request.substringAfter(':')
                lower.startsWith("review team: ") -> request.substringAfter(':')
                else -> request.substringAfter(' ')
            }.trim()
            val result = workforce.run(goal, provider)
            return ConversationReply(result.text, verified = false, status = result.status)
        }

        if (lower.startsWith("agent:") || lower.startsWith("/agent ") || lower.startsWith("do: ")) {
            val task = request.substringAfter(':', request.substringAfter(' ', request)).trim()
            val result = executive.run(task, provider)
            return ConversationReply(result.text, verified = false, status = result.status)
        }

        return conversation.respond(request, provider)
    }

    /** Closes the backing store. Only used for non-shared (test) runtimes; the shared instance lives with the process. */
    fun close() = store.close()

    companion object {
        @Volatile
        private var shared: TruthCoreRuntime? = null

        fun get(context: Context): TruthCoreRuntime =
            shared ?: synchronized(this) {
                shared ?: TruthCoreRuntime(TruthCoreStore(context.applicationContext)).also { shared = it }
            }
    }
}

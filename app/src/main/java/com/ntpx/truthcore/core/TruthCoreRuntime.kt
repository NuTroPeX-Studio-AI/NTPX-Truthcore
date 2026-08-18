package com.ntpx.truthcore.core

import android.content.Context
import com.ntpx.truthcore.core.agent.AgentExecutive
import com.ntpx.truthcore.core.agent.MultiAgentWorkforce
import com.ntpx.truthcore.core.agent.ToolCall
import com.ntpx.truthcore.core.agent.ToolRuntime
import com.ntpx.truthcore.core.chat.ConversationEngine
import com.ntpx.truthcore.core.chat.ConversationReply
import com.ntpx.truthcore.core.data.TruthCoreStore
import com.ntpx.truthcore.core.mcp.McpSession
import com.ntpx.truthcore.core.model.ModelProvider
import com.ntpx.truthcore.core.rag.EvidenceRetriever

class TruthCoreRuntime(context: Context) {
    private val store = TruthCoreStore(context.applicationContext)
    private val retriever = EvidenceRetriever(store)
    private val conversation = ConversationEngine { query -> retriever.retrieve(query) }
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
                    ConversationReply(
                        items.joinToString("\n") { "- ${it.name}: ${it.description}" }.ifBlank { "The MCP server returned no tools." },
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

    fun close() = store.close()
}

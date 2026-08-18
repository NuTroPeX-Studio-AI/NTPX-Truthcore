package com.ntpx.truthcore.core

import android.content.Context
import com.ntpx.truthcore.core.agent.AgentExecutive
import com.ntpx.truthcore.core.agent.ToolCall
import com.ntpx.truthcore.core.agent.ToolRuntime
import com.ntpx.truthcore.core.chat.ConversationEngine
import com.ntpx.truthcore.core.chat.ConversationReply
import com.ntpx.truthcore.core.data.TruthCoreStore
import com.ntpx.truthcore.core.model.ModelProvider
import com.ntpx.truthcore.core.rag.EvidenceRetriever

class TruthCoreRuntime(context: Context) {
    private val store = TruthCoreStore(context.applicationContext)
    private val retriever = EvidenceRetriever(store)
    private val conversation = ConversationEngine { query -> retriever.retrieve(query) }
    private val tools = ToolRuntime(store)
    private val executive = AgentExecutive(tools)

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

        if (lower.startsWith("agent:") || lower.startsWith("/agent ") || lower.startsWith("do: ")) {
            val task = request.substringAfter(':', request.substringAfter(' ', request)).trim()
            val result = executive.run(task, provider)
            return ConversationReply(result.text, verified = false, status = result.status)
        }

        return conversation.respond(request, provider)
    }

    fun close() = store.close()
}

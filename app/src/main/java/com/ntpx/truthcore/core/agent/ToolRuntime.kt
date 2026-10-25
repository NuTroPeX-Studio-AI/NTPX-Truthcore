package com.ntpx.truthcore.core.agent

import com.ntpx.truthcore.core.data.TruthCoreRepository
import com.ntpx.truthcore.core.evidence.Evidence
import com.ntpx.truthcore.core.evidence.EvidenceSanitizer
import com.ntpx.truthcore.core.mcp.McpSession
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class RegisteredTool(
    val name: String,
    val description: String,
    val risk: ToolRisk,
    val enabled: Boolean = true,
    val handler: (Map<String, String>) -> String,
)

/**
 * Permissioned tool registry. READ_ONLY tools run immediately; every other risk
 * class requires a single-use, short-lived approval code bound to the exact
 * call fingerprint. Pending calls are shared by the UI and the hands-free
 * service because both use the same process-wide runtime.
 */
class ToolRuntime(private val store: TruthCoreRepository) {
    private val lock = Any()
    private val tools = linkedMapOf<String, RegisteredTool>()
    private val pending = linkedMapOf<String, ToolCall>()

    init {
        register(RegisteredTool("clock.now", "Return current UTC time", ToolRisk.READ_ONLY) {
            Instant.now().toString()
        })
        register(RegisteredTool("memory.search", "Search persistent memory. arg: query", ToolRisk.READ_ONLY) { args ->
            store.searchMemory(args["query"].orEmpty()).joinToString("\n") { "- ${it.content}" }.ifBlank { "No matching memory." }
        })
        register(RegisteredTool("knowledge.search", "Search trusted local knowledge. arg: query", ToolRisk.READ_ONLY) { args ->
            store.searchKnowledge(args["query"].orEmpty()).joinToString("\n") { "- ${it.label}: ${it.content}" }.ifBlank { "No matching knowledge." }
        })
        register(RegisteredTool("memory.remember", "Persist user-provided memory. arg: content", ToolRisk.WRITE_LOCAL) { args ->
            val content = args["content"].orEmpty().trim()
            require(content.isNotBlank()) { "content is required" }
            require(content.length <= 4000) { "content is too long" }
            "Saved memory ${store.remember(content).id}."
        })
        register(RegisteredTool("memory.forget", "Delete memories containing every query word, or by id. arg: query", ToolRisk.WRITE_LOCAL) { args ->
            val query = args["query"].orEmpty().trim()
            require(query.isNotBlank()) { "query is required" }
            val removed = store.forgetMemory(query)
            if (removed == 0) "No memory matched." else "Deleted $removed memor${if (removed == 1) "y" else "ies"}."
        })
        register(RegisteredTool("knowledge.add", "Add local knowledge. args: label, content, source", ToolRisk.WRITE_LOCAL) { args ->
            val label = args["label"].orEmpty().trim()
            val content = args["content"].orEmpty().trim()
            require(label.isNotBlank() && content.isNotBlank()) { "label and content are required" }
            require(label.length <= 200 && content.length <= 8000) { "label or content is too long" }
            val source = args["source"]?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("memory://") }
            val evidence = Evidence(
                id = UUID.randomUUID().toString(),
                label = label,
                content = content,
                sourceUri = source,
                independentKey = source ?: "local:${UUID.randomUUID()}",
                trust = if (source?.startsWith("https://") == true) 0.85 else 0.75,
            )
            store.upsertKnowledge(evidence)
            "Saved knowledge ${evidence.id}."
        })
        register(RegisteredTool("knowledge.forget", "Delete knowledge containing every query word, or by id. arg: query", ToolRisk.WRITE_LOCAL) { args ->
            val query = args["query"].orEmpty().trim()
            require(query.isNotBlank()) { "query is required" }
            val removed = store.forgetKnowledge(query)
            if (removed == 0) "No knowledge matched." else "Deleted $removed knowledge record${if (removed == 1) "" else "s"}."
        })
        register(RegisteredTool("mcp.call", "Call a configured MCP tool. args: tool, arguments_json", ToolRisk.EXTERNAL) { args ->
            val client = McpSession.client ?: error("No MCP server is connected")
            val tool = args["tool"].orEmpty().trim()
            require(tool.matches(Regex("[A-Za-z0-9_.:/-]{1,200}"))) { "tool is required or invalid" }
            val jsonText = args["arguments_json"].orEmpty().ifBlank { "{}" }
            val arguments = runCatching { JSONObject(jsonText) }.getOrElse { error("arguments_json must be a JSON object") }
            val result = client.callTool(tool, arguments)
            if (!result.success) error(result.error ?: "MCP tool call failed")
            val sanitized = EvidenceSanitizer.sanitize(result.text.take(20_000))
            if (sanitized.quarantined) "[MCP output quarantined: ${sanitized.flags.joinToString()}]" else sanitized.text
        })
    }

    fun register(tool: RegisteredTool) {
        require(tool.name.matches(Regex("[a-z0-9_.-]+")))
        synchronized(lock) { tools[tool.name] = tool }
    }

    fun descriptions(): String = synchronized(lock) {
        tools.values.filter { it.enabled }.joinToString("\n") { "${it.name} [${it.risk}] - ${it.description}" }
    }

    fun risk(name: String): ToolRisk? = synchronized(lock) { tools[name]?.risk }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    fun execute(call: ToolCall, approvalToken: String? = null): ToolExecution {
        val tool = synchronized(lock) { tools[call.name] } ?: return ToolExecution(false, "Unknown tool: ${call.name}")
        if (!tool.enabled) return ToolExecution(false, "Tool disabled: ${call.name}")
        val fingerprint = call.fingerprint()

        if (tool.risk != ToolRisk.READ_ONLY) {
            if (approvalToken == null) {
                val token = store.issueApproval(fingerprint)
                synchronized(lock) { pending[token] = call }
                store.appendAudit("tool.approval_required", fingerprint)
                return ToolExecution(
                    success = false,
                    output = "Approval required for ${call.name}${summary(call)}. Say or type: approve $token",
                    approvalRequired = true,
                    approvalToken = token,
                )
            }
            val pendingCall = synchronized(lock) { pending[approvalToken] }
                ?: return ToolExecution(false, "Approval token is unknown or no longer pending.")
            if (pendingCall.fingerprint() != fingerprint || !store.consumeApproval(approvalToken, fingerprint)) {
                synchronized(lock) { pending.remove(approvalToken) }
                return ToolExecution(false, "Approval token is invalid, expired, used, or bound to another action.")
            }
            synchronized(lock) { pending.remove(approvalToken) }
        }

        return runCatching {
            val output = tool.handler(call.arguments)
            store.appendAudit("tool.executed", "$fingerprint|$output")
            ToolExecution(true, output)
        }.getOrElse {
            store.appendAudit("tool.failed", "$fingerprint|${it.javaClass.simpleName}")
            ToolExecution(false, "Tool failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun approve(token: String): ToolExecution {
        val clean = token.trim().replace(Regex("[\\s-]"), "")
        val call = synchronized(lock) { pending[clean] } ?: return ToolExecution(false, "No pending action for that approval code.")
        return execute(call, clean)
    }

    /** Cancels every pending approval (used by "cancel" / "deny all"). */
    fun cancelPending(): Int = synchronized(lock) {
        val count = pending.size
        pending.clear()
        count
    }

    private fun summary(call: ToolCall): String {
        val preview = call.arguments.entries.joinToString(", ") { (k, v) -> "$k=\"${v.take(60)}${if (v.length > 60) "…" else ""}\"" }
        return if (preview.isBlank()) "" else " ($preview)"
    }
}

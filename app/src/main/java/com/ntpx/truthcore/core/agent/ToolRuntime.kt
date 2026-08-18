package com.ntpx.truthcore.core.agent

import com.ntpx.truthcore.core.data.TruthCoreStore
import com.ntpx.truthcore.core.evidence.Evidence
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

class ToolRuntime(private val store: TruthCoreStore) {
    private val tools = linkedMapOf<String, RegisteredTool>()
    private val pending = linkedMapOf<String, ToolCall>()

    init {
        register(RegisteredTool("clock.now", "Return current UTC time", ToolRisk.READ_ONLY) {
            Instant.now().toString()
        })
        register(RegisteredTool("memory.search", "Search persistent memory. arg: query", ToolRisk.READ_ONLY) { args ->
            val query = args["query"].orEmpty()
            store.searchMemory(query).joinToString("\n") { "- ${it.content}" }.ifBlank { "No matching memory." }
        })
        register(RegisteredTool("knowledge.search", "Search trusted local knowledge. arg: query", ToolRisk.READ_ONLY) { args ->
            val query = args["query"].orEmpty()
            store.searchKnowledge(query).joinToString("\n") { "- ${it.label}: ${it.content}" }.ifBlank { "No matching knowledge." }
        })
        register(RegisteredTool("memory.remember", "Persist user-provided memory. arg: content", ToolRisk.WRITE_LOCAL) { args ->
            val content = args["content"].orEmpty().trim()
            require(content.isNotBlank()) { "content is required" }
            val record = store.remember(content)
            "Saved memory ${record.id}."
        })
        register(RegisteredTool("knowledge.add", "Add local knowledge. args: label, content, source", ToolRisk.WRITE_LOCAL) { args ->
            val label = args["label"].orEmpty().trim()
            val content = args["content"].orEmpty().trim()
            require(label.isNotBlank() && content.isNotBlank()) { "label and content are required" }
            val source = args["source"]?.takeIf { it.startsWith("https://") || it.startsWith("memory://") }
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
        register(RegisteredTool("mcp.call", "Call a configured MCP tool. args: tool, arguments_json", ToolRisk.EXTERNAL) { args ->
            val client = McpSession.client ?: error("No MCP server is connected")
            val tool = args["tool"].orEmpty().trim()
            require(tool.matches(Regex("[A-Za-z0-9_.:/-]{1,200}"))) { "tool is required or invalid" }
            val jsonText = args["arguments_json"].orEmpty().ifBlank { "{}" }
            val arguments = runCatching { JSONObject(jsonText) }
                .getOrElse { error("arguments_json must be a JSON object") }
            val result = client.callTool(tool, arguments)
            if (!result.success) error(result.error ?: "MCP tool call failed")
            result.text
        })
    }

    fun register(tool: RegisteredTool) {
        require(tool.name.matches(Regex("[a-z0-9_.-]+")))
        tools[tool.name] = tool
    }

    fun descriptions(): String = tools.values.filter { it.enabled }.joinToString("\n") {
        "${it.name} [${it.risk}] - ${it.description}"
    }

    fun execute(call: ToolCall, approvalToken: String? = null): ToolExecution {
        val tool = tools[call.name] ?: return ToolExecution(false, "Unknown tool: ${call.name}")
        if (!tool.enabled) return ToolExecution(false, "Tool disabled: ${call.name}")
        val fingerprint = call.fingerprint()

        if (tool.risk != ToolRisk.READ_ONLY) {
            if (approvalToken == null) {
                val token = store.issueApproval(fingerprint)
                pending[token] = call
                store.appendAudit("tool.approval_required", fingerprint)
                return ToolExecution(
                    success = false,
                    output = "Approval required for ${call.name}. Say or type: approve $token",
                    approvalRequired = true,
                    approvalToken = token,
                )
            }
            val pendingCall = pending[approvalToken]
                ?: return ToolExecution(false, "Approval token is unknown or no longer pending.")
            if (pendingCall.fingerprint() != fingerprint || !store.consumeApproval(approvalToken, fingerprint)) {
                return ToolExecution(false, "Approval token is invalid, expired, used, or bound to another action.")
            }
            pending.remove(approvalToken)
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
        val call = pending[token] ?: return ToolExecution(false, "No pending action for that approval token.")
        return execute(call, token)
    }
}

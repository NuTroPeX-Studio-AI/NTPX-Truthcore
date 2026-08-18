package com.ntpx.truthcore.core.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

data class McpConfig(
    val endpoint: String,
    val bearerToken: String = "",
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 90_000,
)

data class McpTool(
    val name: String,
    val description: String = "",
    val inputSchema: String = "{}",
)

data class McpResult(
    val success: Boolean,
    val text: String = "",
    val error: String? = null,
)

class McpClient(private val config: McpConfig) {
    fun validate(): String? {
        val uri = runCatching { URI(config.endpoint.trim()) }.getOrNull() ?: return "MCP endpoint is invalid"
        if (!uri.scheme.equals("https", ignoreCase = true)) return "MCP endpoint must use HTTPS"
        if (uri.host.isNullOrBlank()) return "MCP endpoint must include a host"
        if (uri.userInfo != null) return "MCP endpoint must not embed credentials"
        return null
    }

    fun listTools(): Result<List<McpTool>> {
        validate()?.let { return Result.failure(IllegalArgumentException(it)) }
        val id = UUID.randomUUID().toString()
        val params = JSONObject().put("_meta", clientMeta())
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", "tools/list")
            .put("params", params)
        val response = send("tools/list", null, request)
        if (!response.success) return Result.failure(IllegalStateException(response.error ?: "MCP tools/list failed"))
        return runCatching {
            val root = JSONObject(response.text)
            root.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "MCP error")) }
            val tools = root.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
            buildList {
                for (i in 0 until tools.length()) {
                    val tool = tools.optJSONObject(i) ?: continue
                    val toolName = tool.optString("name").trim()
                    if (toolName.isBlank()) continue
                    add(
                        McpTool(
                            name = toolName,
                            description = tool.optString("description"),
                            inputSchema = tool.optJSONObject("inputSchema")?.toString() ?: "{}",
                        )
                    )
                }
            }
        }
    }

    fun callTool(name: String, arguments: JSONObject = JSONObject()): McpResult {
        validate()?.let { return McpResult(false, error = it) }
        if (!name.matches(Regex("[A-Za-z0-9_.:/-]{1,200}"))) return McpResult(false, error = "MCP tool name is invalid")
        val id = UUID.randomUUID().toString()
        val params = JSONObject()
            .put("name", name)
            .put("arguments", arguments)
            .put("_meta", clientMeta())
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", "tools/call")
            .put("params", params)
        val response = send("tools/call", name, request)
        if (!response.success) return response
        return runCatching {
            val root = JSONObject(response.text)
            root.optJSONObject("error")?.let { return McpResult(false, error = it.optString("message", "MCP error")) }
            McpResult(true, root.opt("result")?.toString() ?: "{}")
        }.getOrElse { McpResult(false, error = "Invalid MCP response: ${it.message}") }
    }

    private fun send(method: String, name: String?, body: JSONObject): McpResult {
        val connection = (URL(config.endpoint.trim()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.connectTimeoutMs
            readTimeout = config.readTimeoutMs
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("MCP-Protocol-Version", PROTOCOL_VERSION)
            setRequestProperty("Mcp-Method", method)
            name?.let { setRequestProperty("Mcp-Name", it) }
            if (config.bearerToken.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.bearerToken}")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) return McpResult(false, error = "MCP HTTP $status: ${raw.take(500)}")
            val contentType = connection.contentType.orEmpty().lowercase()
            val json = if (contentType.contains("text/event-stream")) extractSseJson(raw) else raw.trim()
            if (json.isBlank()) McpResult(false, error = "MCP server returned an empty response") else McpResult(true, json)
        } catch (error: Exception) {
            McpResult(false, error = "MCP request failed: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    private fun extractSseJson(raw: String): String = raw.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("data:") }
        .map { it.removePrefix("data:").trim() }
        .firstOrNull { it.startsWith("{") && it.endsWith("}") }
        .orEmpty()

    private fun clientMeta(): JSONObject = JSONObject().put(
        "io.modelcontextprotocol/clientInfo",
        JSONObject().put("name", "NTPX TruthCore").put("version", "1.0.0-rc1"),
    )

    companion object {
        const val PROTOCOL_VERSION = "2026-07-28"
    }
}

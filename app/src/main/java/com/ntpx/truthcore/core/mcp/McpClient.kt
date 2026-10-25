package com.ntpx.truthcore.core.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
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

/**
 * HTTPS Streamable-HTTP MCP client.
 *
 * Speaks the stateless 2026-07-28 protocol first (per-request `_meta` client
 * info plus `Mcp-Method` / `Mcp-Name` headers). If a server rejects that and
 * asks for a session, the client falls back once to the legacy
 * initialize/initialized handshake and carries the `Mcp-Session-Id` header.
 */
class McpClient(private val config: McpConfig) {
    @Volatile private var legacySessionId: String? = null
    @Volatile private var legacyMode = false

    fun validate(): String? {
        val uri = runCatching { URI(config.endpoint.trim()) }.getOrNull() ?: return "MCP endpoint is invalid"
        if (!uri.scheme.equals("https", ignoreCase = true)) return "MCP endpoint must use HTTPS"
        if (uri.host.isNullOrBlank()) return "MCP endpoint must include a host"
        if (uri.userInfo != null) return "MCP endpoint must not embed credentials"
        return null
    }

    fun listTools(): Result<List<McpTool>> {
        validate()?.let { return Result.failure(IllegalArgumentException(it)) }
        val response = rpc("tools/list", null, JSONObject())
        if (!response.success) return Result.failure(IllegalStateException(response.error ?: "MCP tools/list failed"))
        return runCatching {
            val root = JSONObject(response.text)
            root.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "MCP error")) }
            val tools = root.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
            buildList {
                for (i in 0 until minOf(tools.length(), MAX_TOOLS)) {
                    val tool = tools.optJSONObject(i) ?: continue
                    val toolName = tool.optString("name").trim()
                    if (toolName.isBlank() || !toolName.matches(TOOL_NAME)) continue
                    add(
                        McpTool(
                            name = toolName,
                            description = tool.optString("description").take(1000),
                            inputSchema = tool.optJSONObject("inputSchema")?.toString()?.take(8000) ?: "{}",
                        )
                    )
                }
            }
        }
    }

    fun callTool(name: String, arguments: JSONObject = JSONObject()): McpResult {
        validate()?.let { return McpResult(false, error = it) }
        if (!name.matches(TOOL_NAME)) return McpResult(false, error = "MCP tool name is invalid")
        val params = JSONObject().put("name", name).put("arguments", arguments)
        val response = rpc("tools/call", name, params)
        if (!response.success) return response
        return runCatching {
            val root = JSONObject(response.text)
            root.optJSONObject("error")?.let { return McpResult(false, error = it.optString("message", "MCP error").take(500)) }
            val result = root.optJSONObject("result") ?: return McpResult(true, "{}")
            if (result.optBoolean("isError", false)) return McpResult(false, error = "MCP tool reported an error: ${textContent(result).take(500)}")
            McpResult(true, textContent(result).ifBlank { result.toString() })
        }.getOrElse { McpResult(false, error = "Invalid MCP response: ${it.message}") }
    }

    private fun rpc(method: String, name: String?, params: JSONObject): McpResult {
        if (!legacyMode) {
            val stateless = send(method, name, envelope(method, params.put("_meta", clientMeta())), PROTOCOL_VERSION, null)
            if (stateless.success || !needsLegacy(stateless)) return stateless
            if (!initializeLegacy()) return stateless
        }
        val legacyParams = JSONObject(params.toString()).apply { remove("_meta") }
        val legacy = send(method, name, envelope(method, legacyParams), LEGACY_PROTOCOL_VERSION, legacySessionId)
        if (!legacy.success && legacy.error?.contains("HTTP 404") == true && legacySessionId != null) {
            // Session expired on the server: re-handshake once.
            if (initializeLegacy()) return send(method, name, envelope(method, legacyParams), LEGACY_PROTOCOL_VERSION, legacySessionId)
        }
        return legacy
    }

    private fun needsLegacy(result: McpResult): Boolean {
        val error = result.error?.lowercase() ?: return false
        return listOf("initialize", "session", "protocol version", "unsupported version", "http 400").any(error::contains)
    }

    private fun initializeLegacy(): Boolean {
        legacySessionId = null
        val init = envelope(
            "initialize",
            JSONObject()
                .put("protocolVersion", LEGACY_PROTOCOL_VERSION)
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", CLIENT_NAME).put("version", CLIENT_VERSION)),
        )
        val response = send("initialize", null, init, LEGACY_PROTOCOL_VERSION, null, captureSession = true)
        if (!response.success) return false
        val ok = runCatching { JSONObject(response.text).has("result") }.getOrDefault(false)
        if (!ok) return false
        val notification = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
        send("notifications/initialized", null, notification, LEGACY_PROTOCOL_VERSION, legacySessionId, expectBody = false)
        legacyMode = true
        return true
    }

    private fun envelope(method: String, params: JSONObject) = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", UUID.randomUUID().toString())
        .put("method", method)
        .put("params", params)

    private fun send(
        method: String,
        name: String?,
        body: JSONObject,
        protocolVersion: String,
        sessionId: String?,
        captureSession: Boolean = false,
        expectBody: Boolean = true,
    ): McpResult {
        val connection = try {
            (URL(config.endpoint.trim()).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = config.connectTimeoutMs
                readTimeout = config.readTimeoutMs
                instanceFollowRedirects = false
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/event-stream")
                setRequestProperty("MCP-Protocol-Version", protocolVersion)
                setRequestProperty("Mcp-Method", method)
                name?.let { setRequestProperty("Mcp-Name", it) }
                sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
                if (config.bearerToken.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.bearerToken}")
            }
        } catch (error: Exception) {
            return McpResult(false, error = "MCP request failed: ${redact(error.message ?: error.javaClass.simpleName)}")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status in 300..399) return McpResult(false, error = "MCP endpoint redirects are blocked")
            if (captureSession) connection.getHeaderField("Mcp-Session-Id")?.takeIf { it.length <= 512 }?.let { legacySessionId = it }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.let(::readCapped).orEmpty()
            if (status !in 200..299) return McpResult(false, error = "MCP HTTP $status: ${redact(raw).take(500)}")
            if (!expectBody) return McpResult(true)
            val contentType = connection.contentType.orEmpty().lowercase()
            val json = if (contentType.contains("text/event-stream")) extractSseJson(raw) else raw.trim()
            if (json.isBlank()) McpResult(false, error = "MCP server returned an empty response") else McpResult(true, json)
        } catch (error: Exception) {
            McpResult(false, error = "MCP request failed: ${redact(error.message ?: error.javaClass.simpleName)}")
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(stream: InputStream): String = stream.use { input ->
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_RESPONSE_BYTES) throw IllegalStateException("MCP response exceeds the allowed size")
        }
        out.toString(Charsets.UTF_8.name())
    }

    private fun redact(value: String): String =
        if (config.bearerToken.isBlank()) value else value.replace(config.bearerToken, "***")

    private fun clientMeta(): JSONObject = JSONObject().put(
        "io.modelcontextprotocol/clientInfo",
        JSONObject().put("name", CLIENT_NAME).put("version", CLIENT_VERSION),
    )

    companion object {
        const val PROTOCOL_VERSION = "2026-07-28"
        const val LEGACY_PROTOCOL_VERSION = "2025-06-18"
        private const val CLIENT_NAME = "NTPX TruthCore"
        private const val CLIENT_VERSION = "1.1.0"
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_TOOLS = 256
        private val TOOL_NAME = Regex("[A-Za-z0-9_.:/-]{1,200}")

        /** Takes the last JSON-RPC response object from an SSE stream (servers may emit notifications first). */
        fun extractSseJson(raw: String): String {
            val events = mutableListOf<String>()
            val current = StringBuilder()
            raw.lineSequence().forEach { line ->
                when {
                    line.startsWith("data:") -> current.append(line.removePrefix("data:").trimStart()).append('\n')
                    line.isBlank() && current.isNotEmpty() -> { events += current.toString().trim(); current.clear() }
                }
            }
            if (current.isNotEmpty()) events += current.toString().trim()
            return events.lastOrNull { event ->
                runCatching { JSONObject(event).let { it.has("result") || it.has("error") } }.getOrDefault(false)
            }.orEmpty()
        }

        /** Joins `text` content blocks from a tools/call result. */
        fun textContent(result: JSONObject): String {
            val content = result.optJSONArray("content") ?: return ""
            return buildString {
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    if (block.optString("type") == "text" && block.has("text") && !block.isNull("text")) {
                        if (isNotEmpty()) append('\n')
                        append(block.optString("text"))
                    }
                }
            }
        }
    }
}

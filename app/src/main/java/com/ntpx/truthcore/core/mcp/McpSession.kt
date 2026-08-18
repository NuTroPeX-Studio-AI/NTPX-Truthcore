package com.ntpx.truthcore.core.mcp

/** Process-local MCP session. Bearer credentials are never persisted by TruthCore. */
object McpSession {
    @Volatile
    var client: McpClient? = null
}

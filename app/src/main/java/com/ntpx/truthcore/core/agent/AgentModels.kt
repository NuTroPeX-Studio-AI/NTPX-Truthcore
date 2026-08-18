package com.ntpx.truthcore.core.agent

enum class ToolRisk { READ_ONLY, WRITE_LOCAL, EXTERNAL, PRIVILEGED }

data class ToolCall(
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
) {
    fun fingerprint(): String = buildString {
        append(name)
        arguments.toSortedMap().forEach { (k, v) -> append('|').append(k).append('=').append(v) }
    }
}

data class ToolExecution(
    val success: Boolean,
    val output: String,
    val approvalRequired: Boolean = false,
    val approvalToken: String? = null,
)

data class AgentRun(
    val text: String,
    val status: String,
    val toolExecutions: List<ToolExecution> = emptyList(),
)

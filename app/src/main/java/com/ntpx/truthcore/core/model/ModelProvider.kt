package com.ntpx.truthcore.core.model

data class ModelConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 90_000,
)

data class ModelRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val temperature: Double = 0.2,
)

data class ModelResponse(
    val success: Boolean,
    val text: String = "",
    val error: String? = null,
)

interface ModelProvider {
    val displayName: String
    fun generate(request: ModelRequest): ModelResponse
}

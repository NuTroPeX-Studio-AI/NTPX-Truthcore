package com.ntpx.truthcore.core.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * OpenAI-compatible HTTPS client for chat completions and embeddings.
 * Redirects are refused, bodies are capped, and the API key is redacted from
 * every surfaced error.
 */
class OpenAICompatibleProvider(
    private val config: ModelConfig,
) : ModelProvider {
    override val displayName: String = config.model.trim().ifBlank { "HTTPS chat provider" }

    fun validate(): String? {
        if (config.model.isBlank()) return "Model name is required"
        if (config.model.length > 200 || config.model.contains('\n')) return "Model name is invalid"
        val uri = runCatching { URI(config.baseUrl.trim()) }.getOrNull()
            ?: return "Base URL is invalid"
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return "Only HTTPS model endpoints are allowed"
        }
        if (uri.host.isNullOrBlank()) return "Base URL must include a host"
        if (uri.userInfo != null) return "Credentials must not be embedded in the URL"
        if (uri.fragment != null) return "Base URL must not include a fragment"
        return null
    }

    override fun generate(request: ModelRequest): ModelResponse {
        validate()?.let { return ModelResponse(false, error = it) }
        val payload = JSONObject()
            .put("model", config.model.trim())
            .put("temperature", request.temperature.coerceIn(0.0, 1.0))
            .put("stream", false)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", request.systemPrompt.take(MAX_PROMPT_CHARS)))
                    .put(JSONObject().put("role", "user").put("content", request.userPrompt.take(MAX_PROMPT_CHARS))),
            )
        val response = post("/chat/completions", payload)
        if (!response.success) return ModelResponse(false, error = response.error)
        return runCatching {
            val message = JSONObject(response.body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            val text = extractContent(message).trim()
            if (text.isBlank()) ModelResponse(false, error = "Model returned no text") else ModelResponse(true, text = text)
        }.getOrElse { ModelResponse(false, error = "Model provider returned invalid JSON") }
    }

    /** Calls `/embeddings` with [embeddingModel]; returns null values on any failure. */
    fun embed(text: String, embeddingModel: String): Result<FloatArray> {
        validate()?.let { return Result.failure(IllegalArgumentException(it)) }
        if (embeddingModel.isBlank()) return Result.failure(IllegalArgumentException("Embedding model is required"))
        val response = post("/embeddings", JSONObject().put("model", embeddingModel.trim()).put("input", text.take(8000)))
        if (!response.success) return Result.failure(IllegalStateException(response.error ?: "Embedding request failed"))
        return runCatching {
            val array = JSONObject(response.body).getJSONArray("data").getJSONObject(0).getJSONArray("embedding")
            require(array.length() > 0) { "Embedding provider returned an empty vector" }
            FloatArray(array.length()) { i -> array.getDouble(i).toFloat().also { v -> require(v.isFinite()) { "Non-finite embedding value" } } }
        }
    }

    private data class RawResponse(val success: Boolean, val body: String = "", val error: String? = null)

    private fun post(suffix: String, payload: JSONObject): RawResponse {
        val connection = try {
            endpoint(suffix).openConnection() as HttpsURLConnection
        } catch (t: Throwable) {
            return RawResponse(false, error = "Unable to open model endpoint: ${safeMessage(t)}")
        }
        return try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = config.connectTimeoutMs
            connection.readTimeout = config.readTimeoutMs
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            if (config.apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }

            val code = connection.responseCode
            if (code in 300..399) return RawResponse(false, error = "Model endpoint redirects are blocked")
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.let(::readCapped).orEmpty()
            if (code !in 200..299) {
                val providerMessage = runCatching {
                    val root = JSONObject(body)
                    root.optJSONObject("error")?.optString("message")?.takeUnless { it == "null" }
                        ?: root.optString("message").takeUnless { it == "null" }
                }.getOrNull().orEmpty()
                val detail = providerMessage.ifBlank { "HTTP $code" }
                return RawResponse(false, error = "Model request failed: ${redact(detail).take(220)}")
            }
            RawResponse(true, body = body)
        } catch (t: Throwable) {
            RawResponse(false, error = "Model request failed: ${safeMessage(t)}")
        } finally {
            connection.disconnect()
        }
    }

    private fun endpoint(suffix: String): URL {
        val clean = config.baseUrl.trim().trimEnd('/')
            .removeSuffix("/chat/completions")
            .removeSuffix("/embeddings")
        return URL("$clean$suffix")
    }

    private fun readCapped(stream: InputStream): String = stream.use { input ->
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_RESPONSE_BYTES) throw IllegalStateException("Response body exceeds the allowed size")
        }
        out.toString(Charsets.UTF_8.name())
    }

    private fun safeMessage(t: Throwable): String =
        redact(t.message.orEmpty()).take(220).ifBlank { t::class.java.simpleName }

    private fun redact(value: String): String =
        if (config.apiKey.isBlank()) value else value.replace(config.apiKey, "***")

    companion object {
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_PROMPT_CHARS = 60_000

        /**
         * Extracts assistant text. Handles string content, content-part arrays,
         * and explicit JSON null (org.json's optString would return "null").
         */
        fun extractContent(message: JSONObject?): String {
            if (message == null || !message.has("content") || message.isNull("content")) return ""
            return when (val content = message.get("content")) {
                is String -> content
                is JSONArray -> buildString {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        if (part.has("text") && !part.isNull("text")) append(part.optString("text"))
                    }
                }
                else -> ""
            }
        }
    }
}

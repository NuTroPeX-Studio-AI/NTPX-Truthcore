package com.ntpx.truthcore.core.model

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class OpenAICompatibleProvider(
    private val config: ModelConfig,
) : ModelProvider {
    override val displayName: String = "HTTPS chat provider"

    fun validate(): String? {
        if (config.model.isBlank()) return "Model name is required"
        val uri = runCatching { URI(config.baseUrl.trim()) }.getOrNull()
            ?: return "Base URL is invalid"
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return "Only HTTPS model endpoints are allowed"
        }
        if (uri.host.isNullOrBlank()) return "Base URL must include a host"
        if (uri.userInfo != null) return "Credentials must not be embedded in the URL"
        return null
    }

    override fun generate(request: ModelRequest): ModelResponse {
        validate()?.let { return ModelResponse(false, error = it) }

        val connection = try {
            chatUrl().openConnection() as HttpsURLConnection
        } catch (t: Throwable) {
            return ModelResponse(false, error = "Unable to open model endpoint: ${safeMessage(t)}")
        }

        return try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = config.connectTimeoutMs
            connection.readTimeout = config.readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            if (config.apiKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }

            val payload = JSONObject()
                .put("model", config.model.trim())
                .put("temperature", request.temperature.coerceIn(0.0, 1.0))
                .put("stream", false)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", request.systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", request.userPrompt)),
                )

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }

            val code = connection.responseCode
            if (code in 300..399) {
                return ModelResponse(false, error = "Model endpoint redirects are blocked")
            }

            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }

            if (code !in 200..299) {
                val providerMessage = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
                }.getOrDefault("")
                val detail = providerMessage.ifBlank { "HTTP $code" }
                return ModelResponse(false, error = "Model request failed: ${detail.take(220)}")
            }

            val root = JSONObject(body)
            val text = root
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
                .trim()

            if (text.isBlank()) {
                ModelResponse(false, error = "Model returned no text")
            } else {
                ModelResponse(true, text = text)
            }
        } catch (t: Throwable) {
            ModelResponse(false, error = "Model request failed: ${safeMessage(t)}")
        } finally {
            connection.disconnect()
        }
    }

    private fun chatUrl(): URL {
        val clean = config.baseUrl.trim().trimEnd('/')
        val endpoint = if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
        return URL(endpoint)
    }

    private fun safeMessage(t: Throwable): String =
        t.message?.replace(config.apiKey, "***")?.take(220)?.ifBlank { null }
            ?: t::class.java.simpleName
}

package com.ntpx.truthcore.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.ntpx.truthcore.core.TruthCoreRuntime
import com.ntpx.truthcore.core.TruthCoreVersion
import com.ntpx.truthcore.core.mcp.McpClient
import com.ntpx.truthcore.core.mcp.McpConfig
import com.ntpx.truthcore.core.mcp.McpSession
import com.ntpx.truthcore.core.model.ModelConfig
import com.ntpx.truthcore.core.model.ModelRequest
import com.ntpx.truthcore.core.model.ModelSession
import com.ntpx.truthcore.core.model.OpenAICompatibleProvider
import com.ntpx.truthcore.core.model.VerificationSession
import com.ntpx.truthcore.core.semantic.DeepVerificationGate
import com.ntpx.truthcore.core.semantic.ModelJudgeNliProvider
import com.ntpx.truthcore.core.semantic.OpenAICompatibleEmbeddingProvider
import com.ntpx.truthcore.core.semantic.SemanticVerifier
import com.ntpx.truthcore.core.truth.ClaimAssessment
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class ChatMessage(
    val speaker: Speaker,
    val text: String,
    val status: String? = null,
    val claims: List<ClaimAssessment> = emptyList(),
)

enum class Speaker { USER, TRUTHCORE }

enum class Panel { NONE, COMMANDS, MODEL, VERIFY, MCP }

/**
 * Holds UI state across configuration changes and runs all blocking network
 * and database work off the main thread. Credentials live only in this
 * process: form fields for keys are cleared after a successful connection.
 */
class TruthCoreViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = TruthCoreRuntime.get(application)
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    val messages = mutableStateListOf(
        ChatMessage(
            Speaker.TRUTHCORE,
            "TruthCore ${TruthCoreVersion.VERSION} is ready. ClaimLock, persistent memory, evidence retrieval, permissioned agents, MCP, " +
                "audit chaining, multi-agent review, and user-started hands-free mode are available. Connect a model for open-ended reasoning.",
            "LOCAL",
        )
    )

    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var panel by mutableStateOf(Panel.NONE)
    var speakReplies by mutableStateOf(true)

    // Model
    var baseUrl by mutableStateOf("")
    var modelName by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var modelConnected by mutableStateOf(ModelSession.provider != null)
        private set
    var providerStatus by mutableStateOf(ModelSession.provider?.let { "Model connected: ${it.displayName}" } ?: "Model disconnected")
        private set

    // Verification
    var nliEnabled by mutableStateOf(VerificationSession.semanticGate != null)
    var nliModel by mutableStateOf("")
    var requireSemantic by mutableStateOf(VerificationSession.requireSemantic)
    var embeddingModel by mutableStateOf("")
    var verificationStatus by mutableStateOf(describeVerification())
        private set

    // MCP
    var mcpEndpoint by mutableStateOf("")
    var mcpToken by mutableStateOf("")
    var mcpConnected by mutableStateOf(McpSession.client != null)
        private set
    var mcpStatus by mutableStateOf(if (McpSession.client != null) "MCP connected" else "MCP disconnected")
        private set

    /** Emits the text of each TruthCore reply so the activity can speak it. */
    var onReply: ((String) -> Unit)? = null

    fun togglePanel(target: Panel) {
        panel = if (panel == target) Panel.NONE else target
    }

    fun submit() {
        val request = input.trim()
        if (request.isBlank() || busy) return
        messages += ChatMessage(Speaker.USER, request)
        input = ""
        busy = true
        val provider = ModelSession.provider
        worker.execute {
            val reply = runCatching { runtime.respond(request, provider) }
                .getOrElse { com.ntpx.truthcore.core.chat.ConversationReply("Request failed: ${it.message ?: it.javaClass.simpleName}", false, "ACTION_FAILED") }
            main.post {
                busy = false
                messages += ChatMessage(Speaker.TRUTHCORE, reply.text, reply.status, reply.claims)
                if (speakReplies) onReply?.invoke(reply.text)
            }
        }
    }

    fun connectModel() {
        val candidate = OpenAICompatibleProvider(ModelConfig(baseUrl, modelName, apiKey))
        candidate.validate()?.let { providerStatus = it; return }
        if (busy) return
        busy = true
        providerStatus = "Testing model connection…"
        worker.execute {
            val test = candidate.generate(
                ModelRequest(
                    systemPrompt = "This is a connectivity test. Reply with the single word READY.",
                    userPrompt = "Connectivity test",
                    temperature = 0.0,
                )
            )
            main.post {
                busy = false
                if (test.success) {
                    ModelSession.provider = candidate
                    connectedBaseUrl = baseUrl.trim()
                    connectedModel = modelName.trim()
                    connectedKey = apiKey
                    apiKey = ""
                    modelConnected = true
                    providerStatus = "Model connected: ${modelName.trim()}"
                    applyVerification()
                } else {
                    ModelSession.provider = null
                    modelConnected = false
                    providerStatus = test.error ?: "Model connection test failed"
                }
            }
        }
    }

    fun disconnectModel() {
        ModelSession.provider = null
        VerificationSession.clear()
        modelConnected = false
        apiKey = ""
        connectedKey = ""
        nliEnabled = false
        requireSemantic = false
        providerStatus = "Model disconnected"
        verificationStatus = describeVerification()
    }

    /**
     * Rebuilds the semantic gate and embedding ranker from the current form.
     * Both reuse the connected endpoint/key; the NLI judge may use a different model.
     */
    fun applyVerification() {
        if (!modelConnected || connectedBaseUrl.isBlank()) {
            VerificationSession.clear()
            if (nliEnabled || embeddingModel.isNotBlank()) {
                verificationStatus = "Connect a model first; verification backends reuse its endpoint."
            } else {
                verificationStatus = describeVerification()
            }
            return
        }
        VerificationSession.semanticGate = if (nliEnabled) {
            val judgeModel = nliModel.trim().ifBlank { connectedModel }
            val judge = OpenAICompatibleProvider(ModelConfig(connectedBaseUrl, judgeModel, connectedKey))
            DeepVerificationGate(SemanticVerifier(ModelJudgeNliProvider(judge)))
        } else null
        VerificationSession.requireSemantic = nliEnabled && requireSemantic
        VerificationSession.embeddings = embeddingModel.trim().takeIf { it.isNotBlank() }?.let {
            OpenAICompatibleEmbeddingProvider(OpenAICompatibleProvider(ModelConfig(connectedBaseUrl, connectedModel, connectedKey)), it)
        }
        verificationStatus = describeVerification()
    }

    // Process-memory copy of the connected endpoint/key for verification backends.
    // The visible key field is cleared after a successful connection.
    private var connectedBaseUrl: String = ""
    private var connectedModel: String = ""
    private var connectedKey: String = ""

    fun connectMcp() {
        val candidate = McpClient(McpConfig(endpoint = mcpEndpoint, bearerToken = mcpToken))
        candidate.validate()?.let { mcpStatus = it; return }
        if (busy) return
        busy = true
        mcpStatus = "Testing MCP connection…"
        worker.execute {
            val result = candidate.listTools()
            main.post {
                busy = false
                result.fold(
                    onSuccess = { tools ->
                        McpSession.client = candidate
                        mcpConnected = true
                        mcpToken = ""
                        mcpStatus = "MCP connected · ${tools.size} tool${if (tools.size == 1) "" else "s"}"
                    },
                    onFailure = {
                        McpSession.client = null
                        mcpConnected = false
                        mcpStatus = it.message ?: "MCP connection test failed"
                    },
                )
            }
        }
    }

    fun disconnectMcp() {
        McpSession.client = null
        mcpConnected = false
        mcpToken = ""
        mcpStatus = "MCP disconnected"
    }

    fun postSystemMessage(text: String) {
        messages += ChatMessage(Speaker.TRUTHCORE, text, "LOCAL")
    }

    private fun describeVerification(): String {
        val parts = mutableListOf("Deterministic ClaimLock: on")
        parts += when {
            VerificationSession.semanticGate == null -> "NLI gate: off"
            VerificationSession.requireSemantic -> "NLI gate: required"
            else -> "NLI gate: on"
        }
        parts += if (VerificationSession.embeddings != null) "Embedding ranking: on" else "Embedding ranking: off"
        return parts.joinToString(" · ")
    }

    override fun onCleared() {
        worker.shutdownNow()
        onReply = null
        super.onCleared()
    }
}

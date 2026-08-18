package com.ntpx.truthcore

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ntpx.truthcore.core.TruthCoreRuntime
import com.ntpx.truthcore.core.mcp.McpClient
import com.ntpx.truthcore.core.mcp.McpConfig
import com.ntpx.truthcore.core.mcp.McpSession
import com.ntpx.truthcore.core.model.ModelConfig
import com.ntpx.truthcore.core.model.ModelProvider
import com.ntpx.truthcore.core.model.ModelRequest
import com.ntpx.truthcore.core.model.ModelSession
import com.ntpx.truthcore.core.model.OpenAICompatibleProvider
import com.ntpx.truthcore.voice.VoiceController
import com.ntpx.truthcore.voice.VoiceForegroundService
import java.util.concurrent.Executors

data class ChatMessage(val speaker: Speaker, val text: String, val status: String? = null)
enum class Speaker { USER, TRUTHCORE }

class MainActivity : ComponentActivity() {
    private lateinit var voice: VoiceController
    private lateinit var runtime: TruthCoreRuntime
    private var startVoiceAfterPermission = false
    private var startHandsFreeAfterPermission = false
    private val modelExecutor = Executors.newSingleThreadExecutor()

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (startVoiceAfterPermission && ::voice.isInitialized) voice.start()
            if (startHandsFreeAfterPermission) startHandsFreeSession()
        }
        startVoiceAfterPermission = false
        startHandsFreeAfterPermission = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = TruthCoreRuntime(applicationContext)
        setContent {
            val messages = remember {
                mutableStateListOf(
                    ChatMessage(
                        Speaker.TRUTHCORE,
                        "TruthCore v1 runtime is ready. ClaimLock, persistent memory, evidence retrieval, permissioned agents, MCP, audit chaining, multi-agent review, and user-started hands-free mode are available. Connect a model for open-ended reasoning.",
                        "LOCAL",
                    )
                )
            }
            var input by remember { mutableStateOf("") }
            var voiceState by remember { mutableStateOf("Idle") }
            var speakReplies by remember { mutableStateOf(true) }
            var modelProvider by remember { mutableStateOf<ModelProvider?>(ModelSession.provider) }
            var providerStatus by remember {
                mutableStateOf(ModelSession.provider?.let { "Model connected: ${it.displayName}" } ?: "Model disconnected")
            }
            var modelBusy by remember { mutableStateOf(false) }
            var settingsOpen by remember { mutableStateOf(false) }
            var mcpOpen by remember { mutableStateOf(false) }
            var helpOpen by remember { mutableStateOf(false) }
            var baseUrl by remember { mutableStateOf("") }
            var modelName by remember { mutableStateOf("") }
            var apiKey by remember { mutableStateOf("") }
            var mcpEndpoint by remember { mutableStateOf("") }
            var mcpToken by remember { mutableStateOf("") }
            var mcpStatus by remember { mutableStateOf(if (McpSession.client != null) "MCP connected" else "MCP disconnected") }
            var mcpBusy by remember { mutableStateOf(false) }
            val listState = rememberLazyListState()

            DisposableEffect(Unit) {
                voice = VoiceController(
                    context = this@MainActivity,
                    onTranscript = { input = it },
                    onState = { voiceState = it },
                )
                onDispose { voice.destroy() }
            }

            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
            }

            val connectProvider = {
                val candidate = OpenAICompatibleProvider(ModelConfig(baseUrl, modelName, apiKey))
                val validation = candidate.validate()
                if (validation != null) {
                    providerStatus = validation
                } else if (!modelBusy) {
                    modelBusy = true
                    providerStatus = "Testing model connection…"
                    modelExecutor.execute {
                        val test = candidate.generate(
                            ModelRequest(
                                systemPrompt = "This is a connectivity test. Reply with the single word READY.",
                                userPrompt = "Connectivity test",
                                temperature = 0.0,
                            )
                        )
                        runOnUiThread {
                            modelBusy = false
                            if (test.success) {
                                modelProvider = candidate
                                ModelSession.provider = candidate
                                providerStatus = "Model connected: ${modelName.trim()}"
                            } else {
                                modelProvider = null
                                ModelSession.provider = null
                                providerStatus = test.error ?: "Model connection test failed"
                            }
                        }
                    }
                }
            }

            val connectMcp = {
                val candidate = McpClient(McpConfig(endpoint = mcpEndpoint, bearerToken = mcpToken))
                val validation = candidate.validate()
                if (validation != null) {
                    mcpStatus = validation
                } else if (!mcpBusy) {
                    mcpBusy = true
                    mcpStatus = "Testing MCP connection…"
                    modelExecutor.execute {
                        val result = candidate.listTools()
                        runOnUiThread {
                            mcpBusy = false
                            result.fold(
                                onSuccess = { tools ->
                                    McpSession.client = candidate
                                    mcpToken = ""
                                    mcpStatus = "MCP connected · ${tools.size} tool${if (tools.size == 1) "" else "s"}"
                                },
                                onFailure = {
                                    McpSession.client = null
                                    mcpStatus = it.message ?: "MCP connection test failed"
                                },
                            )
                        }
                    }
                }
            }

            val submit = {
                val request = input.trim()
                if (request.isNotBlank() && !modelBusy && !mcpBusy) {
                    messages += ChatMessage(Speaker.USER, request)
                    input = ""
                    modelBusy = true
                    val providerSnapshot = modelProvider
                    modelExecutor.execute {
                        val reply = runtime.respond(request, providerSnapshot)
                        runOnUiThread {
                            modelBusy = false
                            messages += ChatMessage(Speaker.TRUTHCORE, reply.text, reply.status)
                            if (speakReplies && ::voice.isInitialized) voice.speak(reply.text)
                        }
                    }
                }
            }

            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Header(voiceState, modelProvider != null, McpSession.client != null, modelBusy || mcpBusy)

                        Text(providerStatus, style = MaterialTheme.typography.bodySmall)
                        Text(mcpStatus, style = MaterialTheme.typography.bodySmall)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TextButton(onClick = { helpOpen = !helpOpen }, modifier = Modifier.weight(1f)) {
                                Text(if (helpOpen) "Hide commands" else "Commands")
                            }
                            TextButton(onClick = { settingsOpen = !settingsOpen }, modifier = Modifier.weight(1f)) {
                                Text(if (settingsOpen) "Hide model" else "Model")
                            }
                            TextButton(onClick = { mcpOpen = !mcpOpen }, modifier = Modifier.weight(1f)) {
                                Text(if (mcpOpen) "Hide MCP" else "MCP")
                            }
                        }

                        if (helpOpen) RuntimeCommandsCard()

                        if (settingsOpen) {
                            ModelSettingsPanel(
                                baseUrl, modelName, apiKey, modelBusy, modelProvider != null,
                                onBaseUrlChange = { baseUrl = it },
                                onModelNameChange = { modelName = it },
                                onApiKeyChange = { apiKey = it },
                                onConnect = connectProvider,
                                onDisconnect = {
                                    modelProvider = null
                                    ModelSession.provider = null
                                    apiKey = ""
                                    providerStatus = "Model disconnected"
                                },
                            )
                        }

                        if (mcpOpen) {
                            McpSettingsPanel(
                                endpoint = mcpEndpoint,
                                token = mcpToken,
                                busy = mcpBusy,
                                connected = McpSession.client != null,
                                onEndpointChange = { mcpEndpoint = it },
                                onTokenChange = { mcpToken = it },
                                onConnect = connectMcp,
                                onDisconnect = {
                                    McpSession.client = null
                                    mcpToken = ""
                                    mcpStatus = "MCP disconnected"
                                },
                            )
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(messages) { MessageBubble(it) }
                        }

                        HorizontalDivider()

                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text("Message TruthCore") },
                            placeholder = { Text("Ask, remember, run an agent/team, or use MCP") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 1,
                            maxLines = 4,
                            enabled = !modelBusy && !mcpBusy,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(onClick = submit, enabled = input.isNotBlank() && !modelBusy && !mcpBusy, modifier = Modifier.weight(1f)) {
                                Text(if (modelBusy || mcpBusy) "Working…" else "Send")
                            }
                            OutlinedButton(onClick = { ensureMicThenStart() }, enabled = !modelBusy && !mcpBusy) { Text("Mic") }
                            OutlinedButton(onClick = {
                                if (::voice.isInitialized) {
                                    voice.cancel()
                                    voice.stopSpeaking()
                                }
                            }) { Text("Stop") }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(onClick = { ensureMicThenStartHandsFree() }, modifier = Modifier.weight(1f)) {
                                Text("Start hands-free")
                            }
                            OutlinedButton(onClick = { stopHandsFreeSession() }, modifier = Modifier.weight(1f)) {
                                Text("End hands-free")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text("Speak replies", style = MaterialTheme.typography.labelLarge)
                                Text("Uses Android text to speech", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = speakReplies, onCheckedChange = { speakReplies = it })
                        }

                        Text(
                            "Models and MCP results are untrusted data. Factual answers pass through evidence + ClaimLock. Local writes and remote MCP calls require single-use approval and are audit chained. Hands-free listens only after you start it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        modelExecutor.shutdownNow()
        if (::runtime.isInitialized) runtime.close()
        super.onDestroy()
    }

    private fun ensureMicThenStart() {
        if (!::voice.isInitialized) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voice.start()
        } else {
            startVoiceAfterPermission = true
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun ensureMicThenStartHandsFree() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startHandsFreeSession()
        } else {
            startHandsFreeAfterPermission = true
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startHandsFreeSession() {
        startForegroundService(Intent(this, VoiceForegroundService::class.java))
    }

    private fun stopHandsFreeSession() {
        stopService(Intent(this, VoiceForegroundService::class.java))
    }
}

@Composable
private fun Header(voiceState: String, modelConnected: Boolean, mcpConnected: Boolean, busy: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("NTPX TruthCore", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Cognitive Agent OS · v1.0.0-rc1", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill("Truth ON")
            StatusPill("Voice: $voiceState")
            StatusPill(if (busy) "Runtime: BUSY" else "Model: ${if (modelConnected) "ON" else "OFF"}")
            StatusPill("MCP: ${if (mcpConnected) "ON" else "OFF"}")
        }
    }
}

@Composable
private fun RuntimeCommandsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Runtime commands", style = MaterialTheme.typography.titleMedium)
            Text("remember that <text> → persistent memory (approval gated)", style = MaterialTheme.typography.bodySmall)
            Text("approve <token> → executes one bound pending action", style = MaterialTheme.typography.bodySmall)
            Text("what do you remember about <topic>", style = MaterialTheme.typography.bodySmall)
            Text("search knowledge for <topic>", style = MaterialTheme.typography.bodySmall)
            Text("add knowledge: <label> | <content> → approval gated", style = MaterialTheme.typography.bodySmall)
            Text("agent: <goal> → bounded model-planned tool run", style = MaterialTheme.typography.bodySmall)
            Text("team: <goal> → planner + critic + reviewer", style = MaterialTheme.typography.bodySmall)
            Text("mcp status · mcp list", style = MaterialTheme.typography.bodySmall)
            Text("mcp call <tool> <json> → remote call, approval gated", style = MaterialTheme.typography.bodySmall)
            Text("list tools · audit status", style = MaterialTheme.typography.bodySmall)
            Text("Hands-free wake phrase: Hey TruthCore", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModelSettingsPanel(
    baseUrl: String,
    modelName: String,
    apiKey: String,
    busy: Boolean,
    connected: Boolean,
    onBaseUrlChange: (String) -> Unit,
    onModelNameChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("HTTPS chat provider", style = MaterialTheme.typography.titleMedium)
            Text("Enter an OpenAI-compatible HTTPS base URL. TruthCore appends /chat/completions when needed.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(baseUrl, onBaseUrlChange, label = { Text("Base URL") }, placeholder = { Text("https://provider.example/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            OutlinedTextField(modelName, onModelNameChange, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            OutlinedTextField(apiKey, onApiKeyChange, label = { Text("API key (optional for self-hosted endpoints)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, enabled = !busy && baseUrl.isNotBlank() && modelName.isNotBlank()) { Text(if (connected) "Reconnect & test" else "Connect & test") }
                if (connected) OutlinedButton(onClick = onDisconnect, enabled = !busy) { Text("Disconnect") }
            }
            Text("The API key is never written to preferences, files, logs, GitHub, or saved instance state. A running hands-free service can reuse it only while this app process remains alive.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun McpSettingsPanel(
    endpoint: String,
    token: String,
    busy: Boolean,
    connected: Boolean,
    onEndpointChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MCP server", style = MaterialTheme.typography.titleMedium)
            Text("Connect a Streamable HTTP MCP endpoint over HTTPS. Tool discovery tests the connection; actual MCP tool calls are approval gated.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(endpoint, onEndpointChange, label = { Text("HTTPS MCP endpoint") }, placeholder = { Text("https://mcp.example/mcp") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            OutlinedTextField(token, onTokenChange, label = { Text("Bearer token (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, enabled = !busy && endpoint.isNotBlank()) { Text(if (connected) "Reconnect & test" else "Connect & test") }
                if (connected) OutlinedButton(onClick = onDisconnect, enabled = !busy) { Text("Disconnect") }
            }
            Text("MCP bearer credentials remain process-local only. Server tool descriptions/results are treated as untrusted external data.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Surface(shape = RoundedCornerShape(999.dp), tonalElevation = 2.dp) {
        Text(text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (message.speaker == Speaker.USER) Arrangement.End else Arrangement.Start) {
        Surface(modifier = Modifier.fillMaxWidth(0.9f), shape = RoundedCornerShape(18.dp), tonalElevation = if (message.speaker == Speaker.USER) 4.dp else 1.dp) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (message.speaker == Speaker.USER) "You" else "TruthCore", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    message.status?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

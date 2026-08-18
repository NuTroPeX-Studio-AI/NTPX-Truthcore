package com.ntpx.truthcore

import android.Manifest
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
import com.ntpx.truthcore.core.chat.ConversationEngine
import com.ntpx.truthcore.core.model.ModelConfig
import com.ntpx.truthcore.core.model.ModelProvider
import com.ntpx.truthcore.core.model.ModelRequest
import com.ntpx.truthcore.core.model.OpenAICompatibleProvider
import com.ntpx.truthcore.voice.VoiceController
import java.util.concurrent.Executors

data class ChatMessage(
    val speaker: Speaker,
    val text: String,
    val status: String? = null,
)

enum class Speaker { USER, TRUTHCORE }

class MainActivity : ComponentActivity() {
    private lateinit var voice: VoiceController
    private var startVoiceAfterPermission = false
    private val modelExecutor = Executors.newSingleThreadExecutor()

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && startVoiceAfterPermission && ::voice.isInitialized) voice.start()
        startVoiceAfterPermission = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val engine = remember { ConversationEngine() }
            val messages = remember {
                mutableStateListOf(
                    ChatMessage(
                        speaker = Speaker.TRUTHCORE,
                        text = "TruthCore is ready. The truth gate stays between the model and factual release. Open Model settings to connect an HTTPS provider.",
                        status = "LOCAL",
                    )
                )
            }
            var input by remember { mutableStateOf("") }
            var voiceState by remember { mutableStateOf("Idle") }
            var speakReplies by remember { mutableStateOf(true) }
            var modelProvider by remember { mutableStateOf<ModelProvider?>(null) }
            var providerStatus by remember { mutableStateOf("Disconnected") }
            var modelBusy by remember { mutableStateOf(false) }
            var settingsOpen by remember { mutableStateOf(false) }
            var baseUrl by remember { mutableStateOf("") }
            var modelName by remember { mutableStateOf("") }
            var apiKey by remember { mutableStateOf("") }
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
                val candidate = OpenAICompatibleProvider(
                    ModelConfig(
                        baseUrl = baseUrl,
                        model = modelName,
                        apiKey = apiKey,
                    )
                )
                val validation = candidate.validate()
                if (validation != null) {
                    providerStatus = validation
                } else if (!modelBusy) {
                    modelBusy = true
                    providerStatus = "Testing connection…"
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
                                providerStatus = "Connected: ${modelName.trim()}"
                            } else {
                                modelProvider = null
                                providerStatus = test.error ?: "Connection test failed"
                            }
                        }
                    }
                }
            }

            val submit = {
                val request = input.trim()
                if (request.isNotBlank() && !modelBusy) {
                    messages += ChatMessage(Speaker.USER, request)
                    input = ""
                    modelBusy = true
                    val providerSnapshot = modelProvider
                    modelExecutor.execute {
                        val reply = engine.respond(request, providerSnapshot)
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
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Header(
                            voiceState = voiceState,
                            modelConnected = modelProvider != null,
                            modelBusy = modelBusy,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                providerStatus,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { settingsOpen = !settingsOpen }) {
                                Text(if (settingsOpen) "Hide settings" else "Model settings")
                            }
                        }

                        if (settingsOpen) {
                            ModelSettingsPanel(
                                baseUrl = baseUrl,
                                modelName = modelName,
                                apiKey = apiKey,
                                busy = modelBusy,
                                connected = modelProvider != null,
                                onBaseUrlChange = { baseUrl = it },
                                onModelNameChange = { modelName = it },
                                onApiKeyChange = { apiKey = it },
                                onConnect = connectProvider,
                                onDisconnect = {
                                    modelProvider = null
                                    apiKey = ""
                                    providerStatus = "Disconnected"
                                },
                            )
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(messages) { message ->
                                MessageBubble(message)
                            }
                        }

                        HorizontalDivider()

                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text("Message TruthCore") },
                            placeholder = { Text("Type a message or tap Mic") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 1,
                            maxLines = 4,
                            enabled = !modelBusy,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = submit,
                                enabled = input.isNotBlank() && !modelBusy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (modelBusy) "Working…" else "Send")
                            }
                            OutlinedButton(
                                onClick = { ensureMicThenStart() },
                                enabled = !modelBusy,
                            ) {
                                Text("Mic")
                            }
                            OutlinedButton(onClick = {
                                if (::voice.isInitialized) {
                                    voice.cancel()
                                    voice.stopSpeaking()
                                }
                            }) {
                                Text("Stop")
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
                            "Remote models are untrusted drafts. Factual answers still pass through ClaimLock. API keys remain in volatile app memory only.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        modelExecutor.shutdownNow()
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
}

@Composable
private fun Header(voiceState: String, modelConnected: Boolean, modelBusy: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "NTPX TruthCore",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Android Cognitive Agent OS · v0.5.2 alpha",
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("Truth gate ON")
            StatusPill("Voice: $voiceState")
            StatusPill(
                when {
                    modelBusy -> "Model: BUSY"
                    modelConnected -> "Model: ON"
                    else -> "Model: OFF"
                }
            )
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
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("HTTPS chat provider", style = MaterialTheme.typography.titleMedium)
            Text(
                "Enter the base URL for an OpenAI-compatible HTTPS chat endpoint. TruthCore appends /chat/completions when needed.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("Base URL") },
                placeholder = { Text("https://provider.example/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            OutlinedTextField(
                value = modelName,
                onValueChange = onModelNameChange,
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("API key (optional for self-hosted endpoints)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConnect,
                    enabled = !busy && baseUrl.isNotBlank() && modelName.isNotBlank(),
                ) {
                    Text(if (connected) "Reconnect & test" else "Connect & test")
                }
                if (connected) {
                    OutlinedButton(onClick = onDisconnect, enabled = !busy) {
                        Text("Disconnect")
                    }
                }
            }
            Text(
                "The API key is not written to preferences, files, logs, GitHub, or saved instance state.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        tonalElevation = 2.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.speaker == Speaker.USER) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(18.dp),
            tonalElevation = if (message.speaker == Speaker.USER) 4.dp else 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (message.speaker == Speaker.USER) "You" else "TruthCore",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    message.status?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

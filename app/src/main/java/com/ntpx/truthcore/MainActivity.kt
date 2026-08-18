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
import androidx.compose.ui.unit.dp
import com.ntpx.truthcore.core.chat.ConversationEngine
import com.ntpx.truthcore.voice.VoiceController

data class ChatMessage(
    val speaker: Speaker,
    val text: String,
    val status: String? = null,
)

enum class Speaker { USER, TRUTHCORE }

class MainActivity : ComponentActivity() {
    private lateinit var voice: VoiceController
    private var startVoiceAfterPermission = false

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
                        text = "TruthCore is ready. Ask “status” or “help”, or use the microphone. I will abstain instead of inventing unsupported answers.",
                        status = "LOCAL",
                    )
                )
            }
            var input by remember { mutableStateOf("") }
            var voiceState by remember { mutableStateOf("Idle") }
            var speakReplies by remember { mutableStateOf(true) }
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

            val submit = {
                val request = input.trim()
                if (request.isNotBlank()) {
                    messages += ChatMessage(Speaker.USER, request)
                    input = ""
                    val reply = engine.respond(request)
                    messages += ChatMessage(Speaker.TRUTHCORE, reply.text, reply.status)
                    if (speakReplies && ::voice.isInitialized) voice.speak(reply.text)
                }
            }

            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Header(voiceState = voiceState)

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
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = submit,
                                enabled = input.isNotBlank(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Send")
                            }
                            OutlinedButton(onClick = { ensureMicThenStart() }) {
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
                                Text(
                                    "Uses Android text to speech",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(checked = speakReplies, onCheckedChange = { speakReplies = it })
                        }

                        Text(
                            "Model provider: not connected yet · Truth gate remains active",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
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
private fun Header(voiceState: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "NTPX TruthCore",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Android Cognitive Agent OS · v0.5.1 alpha",
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("Truth gate ON")
            StatusPill("Voice: $voiceState")
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

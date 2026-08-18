package com.ntpx.truthcore

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ntpx.truthcore.core.evidence.Evidence
import com.ntpx.truthcore.core.truth.ClaimLock
import com.ntpx.truthcore.voice.VoiceController

class MainActivity : ComponentActivity() {
    private lateinit var voice: VoiceController
    private var startVoiceAfterPermission = false

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && startVoiceAfterPermission) voice.start()
        startVoiceAfterPermission = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var input by remember { mutableStateOf("") }
            var output by remember { mutableStateOf("TruthCore ready. Verified-only mode is active.") }
            var voiceState by remember { mutableStateOf("Idle") }
            var verifiedOnly by remember { mutableStateOf(true) }

            DisposableEffect(Unit) {
                voice = VoiceController(
                    context = this@MainActivity,
                    onTranscript = { input = it },
                    onState = { voiceState = it },
                )
                onDispose { voice.destroy() }
            }

            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("NTPX TruthCore", style = MaterialTheme.typography.headlineMedium)
                        Text("Android Cognitive Agent OS · v0.5 alpha", style = MaterialTheme.typography.labelLarge)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = verifiedOnly, onCheckedChange = { verifiedOnly = it })
                            Spacer(Modifier.width(8.dp))
                            Text(if (verifiedOnly) "VERIFIED_ONLY" else "Exploratory mode")
                        }

                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text("Ask TruthCore") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                output = demoVerifiedResponse(input, verifiedOnly)
                                voice.speak(output)
                            }) { Text("Send") }
                            OutlinedButton(onClick = { ensureMicThenStart() }) { Text("Mic") }
                            OutlinedButton(onClick = { voice.stop() }) { Text("Stop") }
                        }

                        Text("Voice: $voiceState", style = MaterialTheme.typography.labelMedium)
                        HorizontalDivider()
                        Text("Response", style = MaterialTheme.typography.titleMedium)
                        Text(output)
                        HorizontalDivider()
                        Text(
                            "This Android foundation runs ClaimLock locally. Model/tool adapters are intentionally not granted authority over factual release.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    private fun ensureMicThenStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voice.start()
        } else {
            startVoiceAfterPermission = true
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun demoVerifiedResponse(input: String, strict: Boolean): String {
        if (input.isBlank()) return "Unknown: No request was provided."
        val evidence = listOf(
            Evidence("local-architecture", "TruthCore architecture", "TruthCore uses ClaimLock to withhold unsupported factual claims.", trust = 1.0)
        )
        val draft = if (input.contains("claimlock", ignoreCase = true)) {
            "TruthCore uses ClaimLock to withhold unsupported factual claims [S1]."
        } else {
            "Unknown: I do not yet have a connected evidence source for that request."
        }
        return if (strict) ClaimLock.verify(draft, evidence).answer else draft
    }
}

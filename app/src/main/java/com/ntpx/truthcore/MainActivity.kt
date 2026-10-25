package com.ntpx.truthcore

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ntpx.truthcore.ui.HostActions
import com.ntpx.truthcore.ui.TruthCoreApp
import com.ntpx.truthcore.ui.TruthCoreViewModel
import com.ntpx.truthcore.voice.VoiceController
import com.ntpx.truthcore.voice.VoiceForegroundService

/**
 * Thin host: owns Activity-scoped concerns (permissions, foreground service,
 * speech recognizer/TTS). All conversation state lives in [TruthCoreViewModel]
 * and survives configuration changes.
 */
class MainActivity : ComponentActivity() {
    private val vm: TruthCoreViewModel by viewModels()
    private lateinit var voice: VoiceController
    private var voiceState by mutableStateOf("Idle")
    private var pendingAfterPermission: (() -> Unit)? = null

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] ?: hasPermission(Manifest.permission.RECORD_AUDIO)
        if (micGranted) {
            pendingAfterPermission?.invoke()
        } else {
            vm.postSystemMessage("Microphone permission is required for voice input. Text chat still works.")
        }
        pendingAfterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        voice = VoiceController(
            context = this,
            onTranscript = { vm.input = it },
            onState = { voiceState = it },
        )
        vm.onReply = { text -> voice.speak(text) }

        setContent {
            TruthCoreApp(
                vm = vm,
                voiceState = voiceState,
                host = HostActions(
                    startMic = { withMic { voice.start() } },
                    stopVoice = { voice.cancel(); voice.stopSpeaking() },
                    startHandsFree = { withMic(includeNotifications = true) { startHandsFreeSession() } },
                    stopHandsFree = ::stopHandsFreeSession,
                ),
            )
        }
    }

    override fun onDestroy() {
        vm.onReply = null
        if (::voice.isInitialized) voice.destroy()
        super.onDestroy()
    }

    private fun withMic(includeNotifications: Boolean = false, action: () -> Unit) {
        val needed = buildList {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (includeNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) {
            action()
        } else {
            pendingAfterPermission = action
            requestPermissions.launch(needed.toTypedArray())
        }
    }

    private fun hasPermission(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun startHandsFreeSession() {
        startForegroundService(Intent(this, VoiceForegroundService::class.java))
        vm.postSystemMessage("Hands-free session started. Say “Hey TruthCore” followed by a request. Approval codes can be spoken.")
    }

    private fun stopHandsFreeSession() {
        stopService(Intent(this, VoiceForegroundService::class.java))
    }
}

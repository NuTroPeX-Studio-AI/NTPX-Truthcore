package com.ntpx.truthcore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Callbacks into the hosting activity for things that need an Activity (permissions, services, TTS). */
data class HostActions(
    val startMic: () -> Unit,
    val stopVoice: () -> Unit,
    val startHandsFree: () -> Unit,
    val stopHandsFree: () -> Unit,
)

@Composable
fun TruthCoreApp(vm: TruthCoreViewModel, voiceState: String, host: HostActions) {
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }

    TruthCoreTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Header(voiceState, vm.modelConnected, vm.mcpConnected, vm.busy)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PanelTab("Commands", vm.panel == Panel.COMMANDS, Modifier.weight(1f)) { vm.togglePanel(Panel.COMMANDS) }
                    PanelTab("Model", vm.panel == Panel.MODEL, Modifier.weight(1f)) { vm.togglePanel(Panel.MODEL) }
                    PanelTab("Verify", vm.panel == Panel.VERIFY, Modifier.weight(1f)) { vm.togglePanel(Panel.VERIFY) }
                    PanelTab("MCP", vm.panel == Panel.MCP, Modifier.weight(1f)) { vm.togglePanel(Panel.MCP) }
                }

                when (vm.panel) {
                    Panel.COMMANDS -> RuntimeCommandsCard()
                    Panel.MODEL -> ModelSettingsPanel(vm)
                    Panel.VERIFY -> VerificationPanel(vm)
                    Panel.MCP -> McpSettingsPanel(vm)
                    Panel.NONE -> Unit
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    itemsIndexed(vm.messages) { _, message -> MessageBubble(message) }
                }

                HorizontalDivider()

                OutlinedTextField(
                    value = vm.input,
                    onValueChange = { vm.input = it },
                    label = { Text("Message TruthCore") },
                    placeholder = { Text("Ask, remember, run an agent/team, or use MCP") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 4,
                    enabled = !vm.busy,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = vm::submit, enabled = vm.input.isNotBlank() && !vm.busy, modifier = Modifier.weight(1f)) {
                        Text(if (vm.busy) "Working…" else "Send")
                    }
                    OutlinedButton(onClick = host.startMic, enabled = !vm.busy) { Text("Mic") }
                    OutlinedButton(onClick = host.stopVoice) { Text("Stop") }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = host.startHandsFree, modifier = Modifier.weight(1f)) { Text("Start hands-free") }
                    OutlinedButton(onClick = host.stopHandsFree, modifier = Modifier.weight(1f)) { Text("End hands-free") }
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
                    Switch(checked = vm.speakReplies, onCheckedChange = { vm.speakReplies = it })
                }
            }
        }
    }
}

@Composable
private fun PanelTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, maxLines = 1) }
    } else {
        TextButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, maxLines = 1) }
    }
}

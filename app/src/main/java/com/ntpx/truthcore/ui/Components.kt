package com.ntpx.truthcore.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ntpx.truthcore.core.TruthCoreVersion
import com.ntpx.truthcore.core.truth.ClaimAssessment

private val Teal = Color(0xFF00897B)
private val Coral = Color(0xFFD84315)

@Composable
fun TruthCoreTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF4DB6AC), secondary = Color(0xFFFF8A65))
    } else {
        lightColorScheme(primary = Teal, secondary = Coral)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Header(voiceState: String, modelConnected: Boolean, mcpConnected: Boolean, busy: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("NTPX TruthCore", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Cognitive Agent OS · v${TruthCoreVersion.VERSION}", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill("Truth ON")
            StatusPill("Voice: $voiceState")
            StatusPill(if (busy) "Runtime: BUSY" else "Model: ${if (modelConnected) "ON" else "OFF"}")
            StatusPill("MCP: ${if (mcpConnected) "ON" else "OFF"}")
        }
    }
}

@Composable
fun StatusPill(text: String) {
    Surface(shape = RoundedCornerShape(999.dp), tonalElevation = 2.dp) {
        Text(text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    var showClaims by remember(message) { mutableStateOf(false) }
    val isUser = message.speaker == Speaker.USER
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(18.dp),
            tonalElevation = if (isUser) 4.dp else 1.dp,
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (isUser) "You" else "TruthCore", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    message.status?.let { status ->
                        Text(
                            status,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor(status),
                            modifier = Modifier.semantics { contentDescription = "Status $status" },
                        )
                    }
                }
                SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
                if (message.claims.isNotEmpty()) {
                    Text(
                        if (showClaims) "Hide claim audit" else "Claim audit (${message.claims.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showClaims = !showClaims }.padding(vertical = 2.dp),
                    )
                    if (showClaims) {
                        message.claims.forEach { claim -> ClaimRow(claim) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClaimRow(claim: ClaimAssessment) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp)) {
        Text(
            "${claim.status}${if (claim.sourceIds.isNotEmpty()) " · ${claim.sourceIds.joinToString()}" else ""}${claim.method?.let { " · $it" } ?: ""}",
            style = MaterialTheme.typography.labelSmall,
            color = statusColor(claim.status.name),
        )
        Text(claim.text, style = MaterialTheme.typography.bodySmall)
        Text(claim.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun statusColor(status: String): Color = when (status) {
    "VERIFIED", "SUPPORTED", "ACTION_EXECUTED" -> MaterialTheme.colorScheme.primary
    "ABSTAINED", "UNSUPPORTED", "APPROVAL_REQUIRED", "UNKNOWN" -> MaterialTheme.colorScheme.secondary
    "CONTRADICTED", "PROVIDER_ERROR", "ACTION_FAILED", "ACTION_DENIED", "ALERT" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun RuntimeCommandsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Runtime commands", style = MaterialTheme.typography.titleMedium)
            listOf(
                "remember that <text> → persistent memory (approval gated)",
                "forget <words> → delete memories containing every word (approval gated)",
                "approve <code> → executes one bound pending action",
                "deny · cancel → drops every pending approval",
                "what do you remember about <topic> · memory count",
                "search knowledge for <topic>",
                "add knowledge: <label> | <content> → approval gated",
                "forget knowledge <words> → approval gated",
                "agent: <goal> → bounded model-planned tool run",
                "team: <goal> → planner + critic + reviewer",
                "mcp status · mcp list",
                "mcp call <tool> <json> → remote call, approval gated",
                "list tools · audit status",
                "Hands-free wake phrase: Hey TruthCore",
            ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

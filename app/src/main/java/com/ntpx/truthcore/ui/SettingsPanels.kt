package com.ntpx.truthcore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

@Composable
fun ModelSettingsPanel(vm: TruthCoreViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("HTTPS chat provider", style = MaterialTheme.typography.titleMedium)
            Text(vm.providerStatus, style = MaterialTheme.typography.bodySmall)
            Text("Enter an OpenAI-compatible HTTPS base URL. TruthCore appends /chat/completions when needed.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                vm.baseUrl, { vm.baseUrl = it },
                label = { Text("Base URL") },
                placeholder = { Text("https://provider.example/v1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                enabled = !vm.busy,
            )
            OutlinedTextField(vm.modelName, { vm.modelName = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !vm.busy)
            OutlinedTextField(
                vm.apiKey, { vm.apiKey = it },
                label = { Text("API key (optional for self-hosted endpoints)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
                enabled = !vm.busy,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::connectModel, enabled = !vm.busy && vm.baseUrl.isNotBlank() && vm.modelName.isNotBlank()) {
                    Text(if (vm.modelConnected) "Reconnect & test" else "Connect & test")
                }
                if (vm.modelConnected) OutlinedButton(onClick = vm::disconnectModel, enabled = !vm.busy) { Text("Disconnect") }
            }
            Text(
                "The API key is never written to preferences, files, logs, GitHub, or saved instance state. It is cleared from this form after connecting and lives only in this app process.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun VerificationPanel(vm: TruthCoreViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Verification backends", style = MaterialTheme.typography.titleMedium)
            Text(vm.verificationStatus, style = MaterialTheme.typography.bodySmall)
            ToggleRow(
                title = "Semantic NLI gate",
                subtitle = "A model judges entailment for every claim ClaimLock already supports. It can only withhold, never release.",
                checked = vm.nliEnabled,
                onChange = { vm.nliEnabled = it },
            )
            if (vm.nliEnabled) {
                OutlinedTextField(
                    vm.nliModel, { vm.nliModel = it },
                    label = { Text("Judge model (blank = connected model)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ToggleRow(
                    title = "Require semantic pass",
                    subtitle = "Withhold claims whenever the NLI judge is unavailable or unparseable.",
                    checked = vm.requireSemantic,
                    onChange = { vm.requireSemantic = it },
                )
            }
            OutlinedTextField(
                vm.embeddingModel, { vm.embeddingModel = it },
                label = { Text("Embedding model for evidence ranking (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = vm::applyVerification, enabled = !vm.busy) { Text("Apply") }
            Text(
                "For independent verification, use a different judge model than the drafting model. Embeddings only reorder evidence; they never decide release.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun McpSettingsPanel(vm: TruthCoreViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MCP server", style = MaterialTheme.typography.titleMedium)
            Text(vm.mcpStatus, style = MaterialTheme.typography.bodySmall)
            Text("Connect a Streamable HTTP MCP endpoint over HTTPS. Tool discovery tests the connection; tool calls are approval gated.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                vm.mcpEndpoint, { vm.mcpEndpoint = it },
                label = { Text("HTTPS MCP endpoint") },
                placeholder = { Text("https://mcp.example/mcp") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                enabled = !vm.busy,
            )
            OutlinedTextField(
                vm.mcpToken, { vm.mcpToken = it },
                label = { Text("Bearer token (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
                enabled = !vm.busy,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::connectMcp, enabled = !vm.busy && vm.mcpEndpoint.isNotBlank()) {
                    Text(if (vm.mcpConnected) "Reconnect & test" else "Connect & test")
                }
                if (vm.mcpConnected) OutlinedButton(onClick = vm::disconnectMcp, enabled = !vm.busy) { Text("Disconnect") }
            }
            Text("MCP bearer credentials remain process-local only. Tool descriptions and results are sanitized and treated as untrusted data.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

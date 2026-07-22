package com.remotedev.pocketcode.devservers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

data class DevServer(val pid: Int, val cmd: String, val port: Int?, val managed: Boolean)

@Composable
fun DevServersScreen(
    servers: List<DevServer>,
    logs: Map<Int, String>,
    onRefresh: () -> Unit,
    onStart: (String) -> Unit,
    onFollow: (Int) -> Unit,
    onStop: (Int) -> Unit,
) {
    var command by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { onRefresh() }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (servers.isEmpty()) item { Text("No dev servers found. Start one below to stream its logs.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(servers, key = { it.pid }) { server ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(server.cmd, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text("PID ${server.pid}${server.port?.let { " · port $it" } ?: ""}${if (server.managed) " · managed" else " · unmanaged"}", style = MaterialTheme.typography.labelSmall)
                        server.port?.let { port ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onFollow(port) }) { Text("Follow logs") }
                                if (server.managed) TextButton(onClick = { onStop(server.pid) }) { Text("Stop") }
                            }
                            logs[port]?.let { log ->
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                                    Text(log.takeLast(8_000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Start command") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = { onStart(command); command = "" }, enabled = command.isNotBlank()) { Text("Start") }
        }
    }
}

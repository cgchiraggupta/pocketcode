package com.remotedev.pocketcode.git

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable

@Serializable data class GitStatus(val current: String? = null, val files: List<GitFile> = emptyList())
@Serializable data class GitFile(val path: String, val index: String = " ", val working_dir: String = " ")

@Composable
fun GitPanelScreen(
    status: GitStatus,
    diffText: String,
    onRequestDiff: (String) -> Unit,
    onClearDiff: () -> Unit,
    onStage: (List<String>) -> Unit,
    onCommit: (String) -> Unit,
    onPush: () -> Unit,
    onSwitchBranch: (String) -> Unit
) {
    var msg by remember { mutableStateOf("") }
    var viewingDiffFor by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text("Branch: ${status.current ?: "(none)"}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        status.files.forEach { f ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(f.index + f.working_dir + "  " + f.path, modifier = Modifier.weight(1f))
                TextButton(onClick = { onStage(listOf(f.path)) }) { Text("stage") }
                TextButton(onClick = { viewingDiffFor = f.path }) { Text("diff") }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = msg, onValueChange = { msg = it }, label = { Text("Commit message") }, modifier = Modifier.fillMaxWidth())
        Row {
            Button(onClick = { onCommit(msg); msg = "" }) { Text("Commit") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onPush) { Text("Push") }
        }
    }
    viewingDiffFor?.let { path ->
        LaunchedEffect(path) {
            onRequestDiff(path)
        }
        AlertDialog(
            onDismissRequest = { viewingDiffFor = null; onClearDiff() },
            confirmButton = { TextButton(onClick = { viewingDiffFor = null; onClearDiff() }) { Text("Close") } },
            title = { Text(path) },
            text = { DiffViewer(text = diffText) },
        )
    }
}

@Composable
fun DiffViewer(text: String) {
    val lines = remember(text) { text.split('\n') }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    val add = MaterialTheme.colorScheme.tertiaryContainer
    val del = MaterialTheme.colorScheme.errorContainer
    val hScroll = rememberScrollState()

    Box(Modifier.fillMaxWidth().horizontalScroll(hScroll)) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            if (text.isBlank()) {
                Text("No diff", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            for (line in lines) {
                val bg = when {
                    line.startsWith("+") && !line.startsWith("+++") -> add
                    line.startsWith("-") && !line.startsWith("---") -> del
                    else -> Color.Transparent
                }
                Text(
                    line,
                    style = mono,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 4.dp),
                )
            }
        }
    }
}

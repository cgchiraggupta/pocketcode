package com.remotedev.pocketcode.git

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable

@Serializable data class GitStatus(val current: String? = null, val files: List<GitFile> = emptyList())
@Serializable data class GitFile(val path: String, val index: String = " ", val working_dir: String = " ")

@Composable
fun GitPanelScreen(status: GitStatus, onStage: (List<String>) -> Unit, onCommit: (String) -> Unit, onPush: () -> Unit, onSwitchBranch: (String) -> Unit) {
    var msg by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text("Branch: ${status.current ?: "(none)"}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        status.files.forEach { f ->
            Row {
                Text(f.index + f.working_dir + "  " + f.path, modifier = Modifier.weight(1f))
                TextButton(onClick = { onStage(listOf(f.path)) }) { Text("stage") }
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
}

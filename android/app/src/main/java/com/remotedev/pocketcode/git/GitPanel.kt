package com.remotedev.pocketcode.git

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable

@Serializable data class GitStatus(val current: String? = null, val files: List<GitFile> = emptyList())
@Serializable data class GitFile(val path: String, val index: String = " ", val working_dir: String = " ")

private fun statusColor(index: String, working: String): Color = when {
    index == "A" || working == "A" -> Color(0xFF22C55E)   // added — green
    index == "D" || working == "D" -> Color(0xFFEF4444)   // deleted — red
    index == "?" || working == "?" -> Color(0xFF6B7280)   // untracked — grey
    else                            -> Color(0xFFEAB308)   // modified — amber
}

@Composable
fun GitPanelScreen(
    status: GitStatus,
    diffText: String,
    onRequestDiff: (String) -> Unit,
    onClearDiff: () -> Unit,
    onStage: (List<String>) -> Unit,
    onCommit: (String) -> Unit,
    onPush: () -> Unit,
    onSwitchBranch: (String) -> Unit,
) {
    var msg by remember { mutableStateOf("") }
    var viewingDiffFor by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {

        // ── Header: branch + stage-all ────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "⎷  ${status.current ?: "(detached)"}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status.files.isNotEmpty()) {
                TextButton(onClick = { onStage(status.files.map { it.path }) }) {
                    Text("Stage all", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // ── Changed files (scrollable, takes remaining space above input) ──────
        if (status.files.isEmpty()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Working tree clean",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                items(status.files, key = { it.path }) { f ->
                    val badge = f.index.trim().ifEmpty { f.working_dir.trim() }
                    val color = statusColor(f.index, f.working_dir)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = badge,
                            color = color,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(18.dp),
                        )
                        Text(
                            text = f.path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = { onStage(listOf(f.path)) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) { Text("stage", fontSize = 11.sp) }
                        TextButton(
                            onClick = { viewingDiffFor = f.path },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) { Text("diff", fontSize = 11.sp) }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        // ── Commit row ────────────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = msg,
                onValueChange = { msg = it },
                label = { Text("Commit message") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { if (msg.isNotBlank()) { onCommit(msg); msg = "" } },
                    enabled = msg.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Commit") }
                OutlinedButton(
                    onClick = onPush,
                    modifier = Modifier.weight(1f),
                ) { Text("Push") }
            }
        }
    }

    // ── Diff dialog ───────────────────────────────────────────────────────────
    viewingDiffFor?.let { path ->
        LaunchedEffect(path) { onRequestDiff(path) }
        AlertDialog(
            onDismissRequest = { viewingDiffFor = null; onClearDiff() },
            confirmButton = {
                TextButton(onClick = { viewingDiffFor = null; onClearDiff() }) { Text("Close") }
            },
            title = {
                Text(
                    path.substringAfterLast('/'),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Box(Modifier.heightIn(max = 400.dp)) {
                    DiffViewer(text = diffText)
                }
            },
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
                Text("Loading diff…", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            for (line in lines) {
                val bg = when {
                    line.startsWith("+") && !line.startsWith("+++") -> add
                    line.startsWith("-") && !line.startsWith("---") -> del
                    else -> Color.Transparent
                }
                Text(
                    text = line,
                    style = mono,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .padding(horizontal = 4.dp),
                    softWrap = false,
                )
            }
        }
    }
}

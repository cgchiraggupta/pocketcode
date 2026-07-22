package com.remotedev.pocketcode.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable

@Serializable data class PullRequestSummary(
    val number: Int,
    val title: String,
    val author: String,
    val state: String = "open",
    val draft: Boolean = false,
    val updatedAt: String = "",
    val head: String = "",
    val base: String = "",
)

@Serializable data class PullRequestFile(val filename: String, val status: String, val additions: Int, val deletions: Int, val patch: String? = null)
@Serializable data class PullRequestCommit(val sha: String, val message: String, val author: String)
@Serializable data class PullRequestDetail(
    val number: Int, val title: String, val author: String, val state: String = "open", val draft: Boolean = false,
    val updatedAt: String = "", val head: String = "", val base: String = "", val body: String = "",
    val files: List<PullRequestFile> = emptyList(), val commits: List<PullRequestCommit> = emptyList(),
)

@Composable
fun PullRequestsScreen(
    prs: List<PullRequestSummary>,
    detail: PullRequestDetail?,
    feedback: String?,
    onRefresh: () -> Unit,
    onOpen: (Int) -> Unit,
    onBack: () -> Unit,
    onMerge: (Int) -> Unit,
    onClose: (Int) -> Unit,
) {
    var diff by remember { mutableStateOf<PullRequestFile?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp)) {
            TextButton(onClick = onBack) { Text("Changes") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        if (detail == null) {
            if (prs.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("No open pull requests") }
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                items(prs, key = { it.number }) { pr ->
                    ListItem(
                        headlineContent = { Text("#${pr.number} ${pr.title}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${if (pr.draft) "Draft · " else ""}${pr.author} · ${pr.head} → ${pr.base}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HorizontalDivider()
                    TextButton(onClick = { onOpen(pr.number) }) { Text("Review") }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("#${detail.number} ${detail.title}", style = MaterialTheme.typography.titleLarge)
                    Text("${detail.author} · ${detail.head} → ${detail.base}", style = MaterialTheme.typography.bodySmall)
                    if (detail.body.isNotBlank()) Text(detail.body, modifier = Modifier.padding(top = 8.dp))
                    feedback?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { onMerge(detail.number) }, enabled = !detail.draft) { Text("Squash merge") }
                        OutlinedButton(onClick = { onClose(detail.number) }) { Text("Close PR") }
                    }
                    Text("Commits (${detail.commits.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                }
                items(detail.commits, key = { it.sha }) { commit ->
                    Text(commit.message.lineSequence().firstOrNull().orEmpty(), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text("${commit.sha.take(7)} · ${commit.author}", style = MaterialTheme.typography.labelSmall)
                }
                item { Text("Files changed (${detail.files.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(detail.files, key = { it.filename }) { file ->
                    ListItem(
                        headlineContent = { Text(file.filename, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                        supportingContent = { Text("${file.status} · +${file.additions} −${file.deletions}") },
                        trailingContent = { TextButton(enabled = file.patch != null, onClick = { diff = file }) { Text("Diff") } },
                    )
                }
            }
        }
    }
    diff?.let { file ->
        AlertDialog(onDismissRequest = { diff = null }, title = { Text(file.filename) }, text = { Box(Modifier.heightIn(max = 420.dp)) { DiffViewer(file.patch.orEmpty()) } }, confirmButton = { TextButton(onClick = { diff = null }) { Text("Close") } })
    }
}

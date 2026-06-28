package com.remotedev.pocketcode.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class FsNode(val name: String, val path: String, val type: String, val size: Long = 0, val children: List<FsNode> = emptyList())

@Composable
fun FileTreeScreen(root: StateFlow<List<FsNode>>, onOpen: (FsNode) -> Unit) {
    val nodes by root.collectAsState()
    LazyColumn { items(nodes) { n -> NodeRow(n, onOpen, depth = 0) } }
}

@Composable
private fun NodeRow(node: FsNode, onOpen: (FsNode) -> Unit, depth: Int) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { if (node.type == "dir") open = !open else onOpen(node) }.padding(start = (depth * 16).dp, top = 4.dp, bottom = 4.dp)) {
        Text(if (node.type == "dir") (if (open) "📂" else "📁") else "📄")
        Spacer(Modifier.width(8.dp))
        Text(node.name)
        Spacer(Modifier.weight(1f))
        if (node.type == "file") Text("${node.size} B", style = MaterialTheme.typography.labelSmall)
    }
    if (open && node.children.isNotEmpty()) {
        node.children.forEach { NodeRow(it, onOpen, depth + 1) }
    }
}

// ponytail: code viewer/editor is intentionally a TODO. Drop in a library like
// compose-code-editor or sora-editor for production.
@Composable
fun CodeViewerStub(path: String, content: String) {
    Text("Viewing $path (${content.length} chars) — editor integration TODO")
}

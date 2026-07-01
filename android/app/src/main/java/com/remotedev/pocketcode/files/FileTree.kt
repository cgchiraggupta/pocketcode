package com.remotedev.pocketcode.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// ponytail: in-app code view is a read-only BasicTextField with monospace + line numbers.
// Edit goes through the VS Code extension. Add syntax highlight when needed.
@Composable
fun CodeViewerStub(path: String, content: String) {
    val lines = remember(content) { content.split('\n') }
    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val lineNumStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Light,
    )
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val lineNumBg = MaterialTheme.colorScheme.surfaceVariant

    // ponytail: single vertical scroll wraps both line numbers and code so they
    // stay in lockstep. Horizontal scroll wraps the row so wide lines scroll
    // together with their line numbers.
    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Text(
                path,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )
        }
        Box(Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll)) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.background(lineNumBg)) {
                    lines.forEachIndexed { i, _ ->
                        Text(
                            "${i + 1}",
                            style = lineNumStyle,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                BasicTextField(
                    value = content,
                    onValueChange = {},
                    readOnly = true,
                    textStyle = codeStyle,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

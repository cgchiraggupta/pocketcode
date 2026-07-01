package com.remotedev.pocketcode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.remotedev.pocketcode.PocketcodeApp
import com.remotedev.pocketcode.files.FileTreeScreen
import com.remotedev.pocketcode.git.GitPanelScreen
import com.remotedev.pocketcode.pairing.QrParser
import com.remotedev.pocketcode.pairing.QrScannerScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(openDiffFor: String? = null, clearOpenDiffFor: (String?) -> Unit = {}) {
    val app = PocketcodeApp.instance
    var tab by remember { mutableStateOf(0) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var showWorkspaceDialog by remember { mutableStateOf(false) }
    val machines by app.machines.machines.collectAsState()

    val fileTree by app.connection.fileTree.collectAsState()
    val gitStatus by app.connection.gitStatus.collectAsState()
    val gitDiff by app.connection.gitDiff.collectAsState()
    val agentEvents by app.connection.agentEvents.collectAsState()
    val terminalTabs by app.connection.terminalTabs.collectAsState()
    var activeTerminalTab by remember { mutableStateOf(0) }

    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(openDiffFor) {
        if (openDiffFor != null) {
            tab = 2
            clearOpenDiffFor(null)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (machines.isEmpty()) "PocketCode" else machines.first().name) },
            actions = {
                TextButton(onClick = {
                    app.connection.send("""{"t":"workspace.list"}""")
                    showWorkspaceDialog = true
                }) { Text("Workspaces") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { tab = 5 }) { Text("Machines") }
            },
        )
    }, bottomBar = {
        if (!isLandscape) {
            NavigationBar {
                listOf("Files", "Terminal", "Git", "Agent", "Pair").forEachIndexed { i, label ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, label = { Text(label) }, icon = {})
                }
            }
        }
    }) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (isLandscape) {
                NavigationRail {
                    listOf("Files", "Terminal", "Git", "Agent", "Pair").forEachIndexed { i, label ->
                        NavigationRailItem(selected = tab == i, onClick = { tab = i }, label = { Text(label) }, icon = {})
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                0 -> {
                    // ponytail: pass the upstream StateFlow directly -- wrapping it in
                    // remember(fileTree) { MutableStateFlow(fileTree) } recreates the flow
                    // on every state change and FileTreeScreen never re-receives updates.
                    FileTreeScreen(app.connection.fileTree) { node ->
                        app.connection.send("""{"t":"fs.read","path":"${node.path}"}""")
                    }
                }
                    1 -> com.remotedev.pocketcode.terminal.TerminalScreen(
                        tabs = terminalTabs,
                        activeTab = activeTerminalTab,
                        onActiveTabChange = { activeTerminalTab = it },
                        onAddTab = { app.connection.send("""{"t":"term.open"}""") },
                        onInput = { data ->
                            val curTab = terminalTabs.getOrNull(activeTerminalTab)
                            if (curTab != null) {
                                app.connection.send("""{"t":"term.input","tab":"${curTab.id}","data":${jsonStr(data)}}""")
                            }
                        }
                    )
                    2 -> GitPanelScreen(
                        status = gitStatus,
                        diffText = gitDiff,
                        onRequestDiff = { path -> app.connection.send("""{"t":"git.diff","path":"$path"}""") },
                        onClearDiff = { app.connection.gitDiff.value = "" },
                        onStage = { paths ->
                            val pathsJson = paths.joinToString(",") { jsonStr(it) }
                            app.connection.send("""{"t":"git.stage","paths":[$pathsJson]}""")
                        },
                        onCommit = { msg -> app.connection.send("""{"t":"git.commit","message":${jsonStr(msg)}}""") },
                        onPush = { app.connection.send("""{"t":"git.push"}""") },
                        onSwitchBranch = { name -> app.connection.send("""{"t":"git.checkout","name":${jsonStr(name)}}""") }
                    )
                    3 -> com.remotedev.pocketcode.agent.AgentTimelineScreen(agentEvents)
                    4 -> QrScannerScreen(
                        onPaired = { qr -> app.machines.add(qr); tab = 1 },
                        onManual = { showPasteDialog = true },
                    )
                    5 -> com.remotedev.pocketcode.pairing.MachineListScreen(machines, onPick = { app.connection.connect(it) })
                }
            }
        }
    }

    val openFile by app.connection.openFile.collectAsState()
    openFile?.let { (path, content) ->
        AlertDialog(
            onDismissRequest = { app.connection.openFile.value = null },
            confirmButton = { TextButton(onClick = { app.connection.openFile.value = null }) { Text("Close") } },
            title = { Text(path.substringAfterLast('/')) },
            text = { com.remotedev.pocketcode.files.CodeViewerStub(path = path, content = content) }
        )
    }

    if (showPasteDialog) {
        PasteQrDialog(
            onDismiss = { showPasteDialog = false },
            onSubmit = { raw ->
                QrParser.parse(raw)?.let { qr ->
                    app.machines.add(qr)
                    tab = 1
                }
                showPasteDialog = false
            },
        )
    }

    if (showWorkspaceDialog) {
        val workspaces by app.connection.workspaces.collectAsState()
        AlertDialog(
            onDismissRequest = { showWorkspaceDialog = false },
            title = { Text("Switch Workspace") },
            text = {
                LazyColumn {
                    items(workspaces) { (name, uri) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            app.connection.send("""{"t":"workspace.switch","folderUri":${jsonStr(uri)}}""")
                            showWorkspaceDialog = false
                        }.padding(12.dp)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWorkspaceDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PasteQrDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste pairing string") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Pairing JSON") },
                supportingText = { Text("From your editor's CodeMote panel") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun jsonStr(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

package com.remotedev.pocketcode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotedev.pocketcode.PocketcodeApp
import com.remotedev.pocketcode.connection.ConnState
import com.remotedev.pocketcode.files.FileTreeScreen
import com.remotedev.pocketcode.git.GitPanelScreen
import com.remotedev.pocketcode.pairing.PairingQR
import com.remotedev.pocketcode.pairing.QrParser
import com.remotedev.pocketcode.pairing.QrScannerScreen

// Simple glyph-based icons, matching the existing terminal's unicode-glyph
// convention (⚡ ⏎ ▾) instead of pulling in the material-icons-extended
// artifact just for five icons.
private val NAV_ITEMS = listOf(
    "▤" to "Files",
    ">_" to "Terminal",
    "⎇" to "Git",
    "◆" to "Agent",
    "▣" to "Pair",
)

@Composable
private fun StatusDot(state: ConnState) {
    val color = when (state) {
        is ConnState.Connected -> Color(0xFF22C55E)
        is ConnState.Connecting, is ConnState.Reconnecting -> Color(0xFFEAB308)
        is ConnState.Error -> Color(0xFFEF4444)
        else -> Color(0xFF6B7280)
    }
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

// Floating rounded pill nav (CodeMote-style segmented control) instead of a
// full-bleed stock Material NavigationBar -- selected item picks up the
// brand orange from ClaudeColors rather than the default M3 indicator pill.
@Composable
private fun FloatingBottomNav(selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(24.dp),
        color = cs.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(vertical = 6.dp, horizontal = 4.dp)) {
            NAV_ITEMS.forEachIndexed { i, (glyph, label) ->
                val isSelected = selected == i
                val fg = if (isSelected) cs.primary else cs.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelect(i) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(glyph, fontSize = 17.sp, color = fg)
                    Spacer(Modifier.height(2.dp))
                    Text(label, fontSize = 11.sp, color = fg)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Root(openDiffFor: String? = null, clearOpenDiffFor: (String?) -> Unit = {}) {
    val app = PocketcodeApp.instance
    var tab by remember { mutableStateOf(0) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var showWorkspaceDialog by remember { mutableStateOf(false) }
    val machines by app.machines.machines.collectAsState()
    val connState by app.connection.state.collectAsState()
    val lastConnectUrl by app.connection.lastConnectUrl.collectAsState()

    val fileTree by app.connection.fileTree.collectAsState()
    val gitStatus by app.connection.gitStatus.collectAsState()
    val gitDiff by app.connection.gitDiff.collectAsState()
    val agentEvents by app.connection.agentEvents.collectAsState()
    val terminalTabs by app.connection.terminalTabs.collectAsState()
    val costUpdate by app.connection.costFlow.collectAsState()
    var activeTerminalTab by remember { mutableStateOf(0) }

    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val isImeVisible = WindowInsets.isImeVisible

    LaunchedEffect(openDiffFor) {
        if (openDiffFor != null) {
            tab = 2
            clearOpenDiffFor(null)
        }
    }

    fun pairAndConnect(qr: PairingQR) {
        val machine = app.machines.add(qr)
        app.connection.connect(machine)
        tab = 1
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(connState)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when (val s = connState) {
                                is ConnState.Connected -> s.machine
                                is ConnState.Connecting -> "Connecting…"
                                is ConnState.Reconnecting -> "Reconnecting (${s.attempt})…"
                                is ConnState.Error -> "Connection error"
                                else -> if (machines.isEmpty()) "PocketCode" else machines.first().name
                            }
                        )
                    }
                    val subtitle = when (connState) {
                        is ConnState.Connected -> costUpdate?.let { "Connected · ~\$${"%.4f".format(it.usd)}" } ?: "Connected"
                        is ConnState.Connecting -> "Connecting"
                        is ConnState.Reconnecting -> "Reconnecting"
                        is ConnState.Error -> {
                            val reason = (connState as ConnState.Error).reason
                            if (lastConnectUrl != null) "$reason · $lastConnectUrl" else reason
                        }
                        is ConnState.Disconnected -> "Disconnected"
                        ConnState.Idle -> if (machines.isNotEmpty()) "Tap Machines to connect" else "Scan QR to pair"
                    }
                    Text(subtitle, style = MaterialTheme.typography.labelSmall)
                }
            },
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
        // The keyboard covers the floating navigation. Do not reserve that
        // bar's height as empty space above the IME while entering a command.
        if (!isLandscape && !isImeVisible) {
            FloatingBottomNav(selected = tab, onSelect = { tab = it })
        }
    }) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (isLandscape) {
                NavigationRail {
                    NAV_ITEMS.forEachIndexed { i, (glyph, label) ->
                        NavigationRailItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            label = { Text(label) },
                            icon = { Text(glyph, fontSize = 17.sp) },
                        )
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
                        onCloseTab = { tabId ->
                            app.connection.send("""{"t":"term.close","tab":"$tabId"}""")
                            if (terminalTabs.getOrNull(activeTerminalTab)?.id == tabId) activeTerminalTab = 0
                        },
                        onInput = { data ->
                            val curTab = terminalTabs.getOrNull(activeTerminalTab)
                            if (curTab != null) {
                                app.connection.send("""{"t":"term.input","tab":"${curTab.id}","data":${jsonStr(data)}}""")
                            }
                        },
                        onResize = { tabId, cols, rows ->
                            app.connection.send("""{"t":"term.resize","tab":"$tabId","cols":$cols,"rows":$rows}""")
                        }
                    )
                    2 -> GitPanelScreen(
                        status = gitStatus,
                        diffText = gitDiff,
                        onRequestDiff = { path, staged ->
                            app.connection.send("""{"t":"git.diff","path":${jsonStr(path)},"staged":$staged}""")
                        },
                        onClearDiff = { app.connection.gitDiff.value = "" },
                        onStage = { paths ->
                            val pathsJson = paths.joinToString(",") { jsonStr(it) }
                            app.connection.send("""{"t":"git.stage","paths":[$pathsJson]}""")
                        },
                        onCommit = { msg -> app.connection.send("""{"t":"git.commit","message":${jsonStr(msg)}}""") },
                        onPush = { app.connection.send("""{"t":"git.push"}""") },
                        onSwitchBranch = { name -> app.connection.send("""{"t":"git.checkout","name":${jsonStr(name)}}""") }
                    )
                    3 -> com.remotedev.pocketcode.agent.AgentTimelineScreen(
                        events = agentEvents,
                        onApprove = { tabId -> app.connection.respondToApproval(tabId, approve = true) },
                        onReject = { tabId -> app.connection.respondToApproval(tabId, approve = false) },
                    )
                    4 -> QrScannerScreen(
                        onPaired = { qr -> pairAndConnect(qr) },
                        onManual = { showPasteDialog = true },
                    )
                    5 -> com.remotedev.pocketcode.pairing.MachineListScreen(
                        machines,
                        onPick = { app.connection.connect(it) },
                        onRemove = { app.machines.remove(it.id) },
                    )
                }
            }
        }
    }

    val openFile by app.connection.openFile.collectAsState()
    openFile?.let { (path, content) ->
        com.remotedev.pocketcode.files.FileEditorScreen(
            path = path,
            content = content,
            onSave = { updated ->
                app.connection.send("""{"t":"fs.write","path":${jsonStr(path)},"content":${jsonStr(updated)}}""")
                app.connection.openFile.value = null
            },
            onClose = { app.connection.openFile.value = null },
        )
    }

    if (showPasteDialog) {
        PasteQrDialog(
            onDismiss = { showPasteDialog = false },
            onSubmit = { raw ->
                QrParser.parse(raw)?.let { pairAndConnect(it) }
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
            '' -> sb.append("\\f")
            else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

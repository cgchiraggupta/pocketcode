package com.remotedev.pocketcode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.remotedev.pocketcode.PocketcodeApp
import com.remotedev.pocketcode.files.FileTreeScreen
import com.remotedev.pocketcode.files.FsNode
import com.remotedev.pocketcode.git.GitPanelScreen
import com.remotedev.pocketcode.pairing.QrScannerScreen
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root() {
    val app = PocketcodeApp.instance
    var tab by remember { mutableStateOf(0) }
    var paired by remember { mutableStateOf(false) }
    val machines by app.machines.machines.collectAsState()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (machines.isEmpty()) "PocketCode" else machines.first().name) },
            actions = { TextButton(onClick = { tab = 5 }) { Text("Machines") } },
        )
    }, bottomBar = {
        NavigationBar {
            listOf("Files", "Terminal", "Git", "Agent", "Pair").forEachIndexed { i, label ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, label = { Text(label) }, icon = {})
            }
        }
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> FileTreeScreen(MutableStateFlow(emptyList<FsNode>())) {}
                1 -> com.remotedev.pocketcode.terminal.TerminalScreen { app.connection.send("""{"t":"term.input","tab":"default","data":${'$'}it.quoted}""") }
                2 -> GitPanelScreen(com.remotedev.pocketcode.git.GitStatus(), {}, {}, {}, {})
                3 -> com.remotedev.pocketcode.agent.AgentTimelineScreen(MutableStateFlow(emptyList()))
                4 -> QrScannerScreen(onPaired = { qr -> app.machines.add(qr); paired = true; tab = 1 }, onManual = { /* TODO paste dialog */ })
                5 -> com.remotedev.pocketcode.pairing.MachineListScreen(machines, onPick = { app.connection.connect(it) })
            }
        }
    }
}

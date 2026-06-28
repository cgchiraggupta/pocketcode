package com.remotedev.pocketcode.pairing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MachineListScreen(machines: List<PairedMachine>, onPick: (PairedMachine) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        items(machines) { m ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onPick(m) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium)
                    Text(m.url, style = MaterialTheme.typography.labelSmall)
                    Text("fp: ${m.fingerprint}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

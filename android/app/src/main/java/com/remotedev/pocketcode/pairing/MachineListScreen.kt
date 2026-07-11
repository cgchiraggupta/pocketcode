package com.remotedev.pocketcode.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MachineListScreen(
    machines: List<PairedMachine>,
    onPick: (PairedMachine) -> Unit,
    onRemove: (PairedMachine) -> Unit = {},
) {
    if (machines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No machines paired yet.\nScan a QR code from the editor to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        items(machines, key = { it.id }) { m ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onPick(m) }) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF6B7280))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            m.url,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "fp ${m.fingerprint.take(12)}…",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconTextButton(onClick = { onRemove(m) })
                }
            }
        }
    }
}

@Composable
private fun IconTextButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text("✕", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

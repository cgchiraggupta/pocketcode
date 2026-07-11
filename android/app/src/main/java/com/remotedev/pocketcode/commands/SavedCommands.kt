package com.remotedev.pocketcode.commands

import android.content.Context
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class SavedCommand(
    val id: String,
    val label: String,
    val cmd: String,
)

/**
 * Stores saved one-tap commands in plain SharedPreferences (no secrets here —
 * these are just shell command strings). Exposed as a StateFlow so the UI reacts
 * without polling.
 */
class SavedCommandsStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("pocketcode-saved-commands", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val commands get() = _flow

    fun add(label: String, cmd: String): SavedCommand {
        val entry = SavedCommand(id = UUID.randomUUID().toString(), label = label.trim(), cmd = cmd.trim())
        val next = _flow.value + entry
        save(next)
        _flow.value = next
        return entry
    }

    fun remove(id: String) {
        val next = _flow.value.filter { it.id != id }
        save(next)
        _flow.value = next
    }

    private fun load(): List<SavedCommand> {
        val raw = prefs.getString("commands", "[]") ?: "[]"
        return runCatching { Json.decodeFromString<List<SavedCommand>>(raw) }.getOrDefault(emptyList())
    }

    private fun save(list: List<SavedCommand>) {
        prefs.edit().putString("commands", Json.encodeToString(list)).apply()
    }
}

/**
 * Compact saved-command bar shown above the terminal input row.
 *
 * A horizontal chip row listing all saved commands; tapping one fires it
 * immediately (appends "\r" so the shell executes it, matching how
 * Terminal.kt's submit button works). A "+" chip at the end opens the
 * add-command dialog.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SavedCommandBar(
    commands: List<SavedCommand>,
    onRun: (String) -> Unit,
    onAdd: (label: String, cmd: String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var longPressTarget by remember { mutableStateOf<SavedCommand?>(null) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
        ) {
            commands.forEach { cmd ->
                InputChip(
                    selected = false,
                    onClick = { onRun(cmd.cmd + "\r") },
                    label = {
                        Text(
                            cmd.label,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    },
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .combinedClickable(
                            onClick = { onRun(cmd.cmd + "\r") },
                            onLongClick = { longPressTarget = cmd },
                        ),
                )
            }
        }
        // Add button
        TextButton(
            onClick = { showAddDialog = true },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text("+", fontSize = 18.sp)
        }
    }

    // Long-press delete dialog
    longPressTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { longPressTarget = null },
            title = { Text("Remove command?") },
            text = { Text("\"${target.label}\" will be removed from saved commands.") },
            confirmButton = {
                TextButton(onClick = { onRemove(target.id); longPressTarget = null }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { longPressTarget = null }) { Text("Cancel") }
            },
        )
    }

    // Add dialog
    if (showAddDialog) {
        AddCommandDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { label, cmd -> onAdd(label, cmd); showAddDialog = false },
        )
    }
}

@Composable
private fun AddCommandDialog(onDismiss: () -> Unit, onAdd: (label: String, cmd: String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save command") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text("e.g. Run tests") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cmd,
                    onValueChange = { cmd = it },
                    label = { Text("Command") },
                    placeholder = { Text("e.g. npm test") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(label, cmd) },
                enabled = label.isNotBlank() && cmd.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

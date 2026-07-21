package com.remotedev.pocketcode.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.remotedev.pocketcode.persistence.Note

@Composable
fun NotesScreen(notes: List<Note>, canSend: Boolean, onSave: (Long?, String) -> Unit, onDelete: (Long) -> Unit, onSend: (String) -> Unit) {
    var editing by remember { mutableStateOf<Note?>(null) }
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Notes", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { editing = Note(content = "", updatedAt = 0); draft = "" }) { Text("New note") }
        }
        if (notes.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("Capture a note, then send it to your active agent.") }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(notes, key = { it.id }) { note ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(note.content, style = MaterialTheme.typography.bodyLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = note; draft = note.content }) { Text("Edit") }
                        TextButton(onClick = { onDelete(note.id) }) { Text("Delete") }
                        Button(onClick = { onSend(note.content) }, enabled = canSend) { Text("Send to agent") }
                    }
                }}
            }
        }
    }
    editing?.let { note -> AlertDialog(onDismissRequest = { editing = null }, title = { Text(if (note.id == 0L) "New note" else "Edit note") }, text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(), minLines = 5, label = { Text("Note") }) }, confirmButton = { TextButton(onClick = { onSave(note.id.takeIf { it != 0L }, draft.trim()); editing = null }, enabled = draft.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }) }
}

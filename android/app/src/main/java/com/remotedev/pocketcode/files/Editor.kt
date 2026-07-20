package com.remotedev.pocketcode.files

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val EDITABLE_FILE_LIMIT = 250_000

/** Lightweight mobile editor backed by the existing fs.read/fs.write protocol. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditorScreen(
    path: String,
    content: String,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    var text by remember(path, content) { mutableStateOf(content) }
    var undoStack by remember(path, content) { mutableStateOf(emptyList<String>()) }
    var redoStack by remember(path, content) { mutableStateOf(emptyList<String>()) }
    val editable = content.length <= EDITABLE_FILE_LIMIT
    val dirty = editable && text != content
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().background(cs.background)) {
        TopAppBar(
            title = {
                Column {
                    Text(path.substringAfterLast('/'))
                    Text(
                        when {
                            !editable -> "Read-only: file is too large to edit safely"
                            dirty -> "Unsaved changes"
                            else -> path
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dirty) cs.primary else cs.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = { TextButton(onClick = onClose) { Text("Close") } },
            actions = {
                TextButton(
                    onClick = {
                        val previous = undoStack.lastOrNull() ?: return@TextButton
                        redoStack = (redoStack + text).takeLast(100)
                        undoStack = undoStack.dropLast(1)
                        text = previous
                    },
                    enabled = editable && undoStack.isNotEmpty(),
                ) { Text("Undo") }
                TextButton(onClick = { onSave(text) }, enabled = dirty) { Text("Save") }
            },
        )
        BasicTextField(
            value = text,
            onValueChange = { updated ->
                if (editable && updated != text) {
                    undoStack = (undoStack + text).takeLast(100)
                    redoStack = emptyList()
                    text = updated
                }
            },
            readOnly = !editable,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = cs.onSurface,
            ),
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .verticalScroll(verticalScroll)
                .horizontalScroll(horizontalScroll),
        )
    }
}

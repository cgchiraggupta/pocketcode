package com.remotedev.pocketcode.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val EDITABLE_FILE_LIMIT = 250_000

/** Lightweight mobile editor backed by the existing fs.read/fs.write protocol. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FileEditorScreen(
    path: String,
    content: String,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    var editorValue by remember(path, content) { mutableStateOf(TextFieldValue(content)) }
    var undoStack by remember(path, content) { mutableStateOf(emptyList<String>()) }
    var redoStack by remember(path, content) { mutableStateOf(emptyList<String>()) }
    val editable = content.length <= EDITABLE_FILE_LIMIT
    val dirty = editable && editorValue.text != content
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val imeVisible = WindowInsets.isImeVisible
    val cs = MaterialTheme.colorScheme

    fun insertAtCursor(snippet: String) {
        val selection = editorValue.selection
        val updated = editorValue.text.replaceRange(selection.start, selection.end, snippet)
        undoStack = (undoStack + editorValue.text).takeLast(100)
        redoStack = emptyList()
        editorValue = TextFieldValue(updated, TextRange(selection.start + snippet.length))
        focusRequester.requestFocus()
    }

    Column(Modifier.fillMaxSize().background(cs.background).imePadding()) {
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
                        redoStack = (redoStack + editorValue.text).takeLast(100)
                        undoStack = undoStack.dropLast(1)
                        editorValue = TextFieldValue(previous)
                    },
                    enabled = editable && undoStack.isNotEmpty(),
                ) { Text("Undo") }
                TextButton(onClick = { onSave(editorValue.text) }, enabled = dirty) { Text("Save") }
            },
        )
        BasicTextField(
            value = editorValue,
            onValueChange = { updated ->
                if (editable && updated.text != editorValue.text) {
                    undoStack = (undoStack + editorValue.text).takeLast(100)
                    redoStack = emptyList()
                    editorValue = updated
                } else if (editable) {
                    editorValue = updated
                }
            },
            readOnly = !editable,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = cs.onSurface,
            ),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp)
                .verticalScroll(verticalScroll)
                .horizontalScroll(horizontalScroll)
                .focusRequester(focusRequester),
            visualTransformation = CodeSyntaxHighlighting,
        )
        if (editable && imeVisible) CodeKeyPalette(::insertAtCursor)
    }
}

/** Lightweight, language-neutral highlighting for comments and fenced code blocks. */
private object CodeSyntaxHighlighting : VisualTransformation {
    private val commentStyle = SpanStyle(color = Color(0xFF22C55E))
    private val fenceStyle = SpanStyle(color = Color(0xFFEAB308))

    override fun filter(text: AnnotatedString): TransformedText {
        val out = AnnotatedString.Builder(text)
        var inFence = false
        var start = 0
        for (line in text.text.splitToSequence('\n')) {
            val end = start + line.length
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> {
                    out.addStyle(fenceStyle, start, end)
                    inFence = !inFence
                }
                (inFence && trimmed.startsWith("#")) || trimmed.startsWith("//") || trimmed.startsWith("<!--") ->
                    out.addStyle(commentStyle, start, end)
            }
            start = end + 1
        }
        return TransformedText(out.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@Composable
private fun CodeKeyPalette(onInsert: (String) -> Unit) {
    val keys = listOf("tab" to "\t", "{" to "{", "}" to "}", "[" to "[", "]" to "]", "(" to "(", ")" to ")", "#" to "#", "/" to "/", "_" to "_", "-" to "-")
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        keys.forEach { (label, value) ->
            Surface(
                onClick = { onInsert(value) },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    text = label,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

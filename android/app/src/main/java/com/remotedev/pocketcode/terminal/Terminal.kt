package com.remotedev.pocketcode.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class Tab(
    val id: String,
    val title: String,
    val alive: Boolean = true,
    val lines: List<AnnotatedString> = emptyList()
)

// Minimal ANSI 16-color parser. Extend with 256/truecolor when needed.
private val ANSI = mapOf(
    30 to Color(0xFF1E1E1E), 31 to Color(0xFFEF4444), 32 to Color(0xFF22C55E), 33 to Color(0xFFEAB308),
    34 to Color(0xFF3B82F6), 35 to Color(0xFFA855F7), 36 to Color(0xFF06B6D4), 37 to Color(0xFFE5E5E5),
    90 to Color(0xFF6B7280), 91 to Color(0xFFF87171), 92 to Color(0xFF4ADE80), 93 to Color(0xFFFACC15),
    94 to Color(0xFF60A5FA), 95 to Color(0xFFC084FC), 96 to Color(0xFF22D3EE), 97 to Color(0xFFF5F5F5),
)

private fun get256Color(idx: Int): Color {
    if (idx in 0..15) {
        val mapped = if (idx < 8) 30 + idx else 90 + (idx - 8)
        return ANSI[mapped] ?: Color(0xFFE5E5E5)
    }
    if (idx in 16..231) {
        val r = ((idx - 16) / 36) % 6
        val g = ((idx - 16) / 6) % 6
        val b = (idx - 16) % 6
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        return Color(levels[r], levels[g], levels[b])
    }
    if (idx in 232..255) {
        val g = 8 + (idx - 232) * 10
        return Color(g, g, g)
    }
    return Color(0xFFE5E5E5)
}

fun renderAnsi(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0; var curColor = Color(0xFFE5E5E5)
    while (i < text.length) {
        if (text[i] == '\u001B' && i + 1 < text.length && text[i + 1] == '[') {
            val end = text.indexOf('m', i + 2).takeIf { it > 0 } ?: text.length
            val codeStr = text.substring(i + 2, end)
            val parts = codeStr.split(';').mapNotNull { it.toIntOrNull() }
            
            var pIdx = 0
            while (pIdx < parts.size) {
                val code = parts[pIdx]
                when {
                    code == 0 -> {
                        curColor = Color(0xFFE5E5E5)
                        pIdx++
                    }
                    code == 38 -> {
                        if (pIdx + 1 < parts.size) {
                            val mode = parts[pIdx + 1]
                            if (mode == 5 && pIdx + 2 < parts.size) {
                                val idx = parts[pIdx + 2]
                                curColor = get256Color(idx)
                                pIdx += 3
                            } else if (mode == 2 && pIdx + 4 < parts.size) {
                                val r = parts[pIdx + 2]
                                val g = parts[pIdx + 3]
                                val b = parts[pIdx + 4]
                                curColor = Color(r, g, b)
                                pIdx += 5
                            } else {
                                pIdx++
                            }
                        } else {
                            pIdx++
                        }
                    }
                    code == 39 -> {
                        curColor = Color(0xFFE5E5E5)
                        pIdx++
                    }
                    code in 30..37 -> {
                        ANSI[code]?.let { curColor = it }
                        pIdx++
                    }
                    code in 90..97 -> {
                        ANSI[code]?.let { curColor = it }
                        pIdx++
                    }
                    else -> {
                        pIdx++
                    }
                }
            }
            i = end + 1
        } else {
            pushStyle(SpanStyle(color = curColor))
            append(text[i])
            i++
        }
    }
}

@Composable
fun TerminalScreen(
    tabs: List<Tab>,
    activeTab: Int,
    onActiveTabChange: (Int) -> Unit,
    onAddTab: () -> Unit,
    onInput: (String) -> Unit
) {
    val cur = tabs.getOrNull(activeTab)
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(cur?.lines?.size) {
        val lineCount = cur?.lines?.size ?: 0
        if (lineCount > 0) listState.animateScrollToItem(lineCount - 1)
    }

    fun submitInput() {
        if (input.isEmpty()) return
        onInput(input + "\r")
        input = ""
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            tabs.forEachIndexed { i, t ->
                FilterChip(selected = i == activeTab, onClick = { onActiveTabChange(i) }, label = { Text(t.title) })
                Spacer(Modifier.width(4.dp))
            }
            TextButton(onClick = onAddTab) { Text("+") }
        }

        if (cur != null) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).background(Color(0xFF0E0E10)).padding(8.dp),
            ) {
                items(cur.lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
            ExtraKeys(onSend = { onInput(it) })
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Type command…") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitInput() }),
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { submitInput() }, enabled = input.isNotEmpty()) {
                    Text("Send")
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No active terminal tab. Tap + to open one.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ExtraKeys(onSend: (String) -> Unit) {
    val keys = listOf("Ctrl", "Tab", "Esc", "\u001B[A", "\u001B[B", "\u001B[C", "\u001B[D", "Enter")
    Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        keys.forEach { k ->
            val payload = when (k) {
                "Enter" -> "\r"
                "Tab" -> "\t"
                "Esc" -> "\u001B"
                "Ctrl" -> null    // modifier — handled by long-press in v2
                else -> k
            }
            OutlinedButton(onClick = { if (payload != null) onSend(payload) }) { Text(if (k == "Ctrl") "^" else k, fontSize = 11.sp) }
        }
    }
}

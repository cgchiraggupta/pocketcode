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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotedev.pocketcode.PocketcodeApp
import com.remotedev.pocketcode.commands.SavedCommandBar
import com.remotedev.pocketcode.voice.VoiceInput

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
        if (text[i] == '' && i + 1 < text.length && text[i + 1] == '[') {
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
                                val colorIdx = parts[pIdx + 2]
                                curColor = get256Color(colorIdx)
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
    val ctx = LocalContext.current
    val voice = remember { VoiceInput(ctx) }
    var listening by remember { mutableStateOf(false) }
    val voiceText by voice.text.collectAsState()

    DisposableEffect(Unit) { onDispose { voice.release() } }

    // ponytail: voice recognition result routes through onInput, the same path
    // used by typed text and saved commands. onInput wraps in a term.input WS
    // message, so the recognized text reaches the active PTY (and Claude Code /
    // any other agent CLI running there) as real stdin — not just a local echo.
    LaunchedEffect(voiceText) {
        if (listening && voiceText.isNotEmpty()) {
            onInput(voiceText + "\n")
            voice.text.value = ""
            listening = false
        }
    }

    // Saved commands
    val savedCommands by PocketcodeApp.instance.savedCommands.commands.collectAsState()

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

    var showTabMenu by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E10))
    ) {
        // ── Top bar: "Terminal N ↓"  +  [⚡] [⌨] ─────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1C))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { showTabMenu = true }) {
                    Text(
                        text = if (cur != null) "${cur.title}  ▾" else "Terminal  ▾",
                        color = Color(0xFFE5E5E5),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                    )
                }
                DropdownMenu(
                    expanded = showTabMenu,
                    onDismissRequest = { showTabMenu = false },
                ) {
                    tabs.forEachIndexed { i, t ->
                        DropdownMenuItem(
                            text = { Text(t.title, fontFamily = FontFamily.Monospace) },
                            onClick = { onActiveTabChange(i); showTabMenu = false },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("+ New terminal") },
                        onClick = { onAddTab(); showTabMenu = false },
                    )
                }
            }
            // Lightning bolt: voice input toggle
            TextButton(
                onClick = {
                    if (listening) { voice.stop(); listening = false }
                    else { voice.text.value = ""; voice.start(); listening = true }
                },
            ) {
                Text(
                    text = if (listening) "■" else "⚡",
                    color = if (listening) Color(0xFFEF4444) else Color(0xFFE5E5E5),
                    fontSize = 18.sp,
                )
            }
        }

        if (cur != null) {
            // ── Terminal output ───────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                items(cur.lines) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }

            // ── Saved commands bar ────────────────────────────────────────────
            // Shown even when the command list is empty (shows only the "+" chip),
            // so users discover the feature immediately.
            SavedCommandBar(
                commands = savedCommands,
                onRun = { cmd -> onInput(cmd) },
                onAdd = { label, cmd -> PocketcodeApp.instance.savedCommands.add(label, cmd) },
                onRemove = { id -> PocketcodeApp.instance.savedCommands.remove(id) },
            )

            // ── Extra keys ───────────────────────────────────────────────────
            ExtraKeys(onSend = { onInput(it) })

            // ── Input row ────────────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1C))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = if (listening) "(listening…)" else input,
                    onValueChange = { if (!listening) input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !listening,
                    placeholder = { Text("›", color = Color(0xFF6B7280), fontFamily = FontFamily.Monospace) },
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = Color(0xFFE5E5E5),
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color(0xFF3A3A3C),
                        focusedBorderColor = Color(0xFF6B7280),
                        cursorColor = Color(0xFFE5E5E5),
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitInput() }),
                )
                Spacer(Modifier.width(6.dp))
                FilledTonalButton(
                    onClick = { submitInput() },
                    enabled = input.isNotEmpty() && !listening,
                ) {
                    Text("⏎", fontFamily = FontFamily.Monospace)  // ⏎
                }
            }
        } else {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(onClick = onAddTab) {
                    Text("Tap to open a terminal", color = Color(0xFF6B7280))
                }
            }
        }
    }
}

@Composable
private fun ExtraKeys(onSend: (String) -> Unit) {
    // Matches CodeMote's key bar: esc ctrl ->| ~ | / - left down up right
    val keys = listOf(
        "esc"  to "",
        "ctrl" to "",
        "->|"  to "\t",
        "~"    to "~",
        "|"    to "|",
        "/"    to "/",
        "-"    to "-",
        "<-"   to "[D",
        "v"    to "[B",
        "^"    to "[A",
        "->"   to "[C",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1C))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        keys.forEach { (label, payload) ->
            TextButton(
                onClick = { onSend(payload) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFD1D5DB))
            }
        }
    }
}

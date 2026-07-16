package com.remotedev.pocketcode.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotedev.pocketcode.PocketcodeApp
import com.remotedev.pocketcode.commands.SavedCommandBar

data class Tab(
    val id: String,
    val title: String,
    val alive: Boolean = true,
    // Raw PTY output (post-JSON-decode, pre-ANSI-interpretation). Rendering
    // (colors, cursor movement, TUI redraws) is handled by xterm.js in
    // XtermTerminalView -- see that file for why a hand-rolled line-by-line
    // parser couldn't represent full-screen apps like Codex/Gemini/Claude Code.
    val raw: String = ""
)

@Composable
fun TerminalScreen(
    tabs: List<Tab>,
    activeTab: Int,
    onActiveTabChange: (Int) -> Unit,
    onAddTab: () -> Unit,
    onCloseTab: (String) -> Unit,
    onInput: (String) -> Unit,
    onResize: (tabId: String, cols: Int, rows: Int) -> Unit = { _, _, _ -> },
) {
    // Saved commands
    val savedCommands by PocketcodeApp.instance.savedCommands.commands.collectAsState()

    val cur = tabs.getOrNull(activeTab)
    var input by remember { mutableStateOf("") }

    fun submitInput() {
        if (input.isEmpty()) return
        onInput(input + "\r")
        input = ""
    }

    var showTabMenu by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
    ) {
        // ── Top bar: pill tab selector (tap to switch / close / add tabs) ────
        Row(
            Modifier
                .fillMaxWidth()
                .background(cs.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                Surface(
                    onClick = { showTabMenu = true },
                    shape = RoundedCornerShape(50),
                    color = cs.surfaceVariant,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (cur?.alive == true) Color(0xFF22C55E) else cs.onSurfaceVariant)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (cur != null) "${cur.title}  ▾" else "Terminal  ▾",
                            color = cs.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 15.sp,
                        )
                    }
                }
                DropdownMenu(
                    expanded = showTabMenu,
                    onDismissRequest = { showTabMenu = false },
                ) {
                    tabs.forEachIndexed { i, t ->
                        DropdownMenuItem(
                            text = { Text(t.title, fontFamily = FontFamily.Monospace) },
                            onClick = { onActiveTabChange(i); showTabMenu = false },
                            trailingIcon = {
                                TextButton(onClick = {
                                    onCloseTab(t.id)
                                    showTabMenu = false
                                }) {
                                    Text("✕", color = cs.error, fontSize = 14.sp)
                                }
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("+ New terminal") },
                        onClick = { onAddTab(); showTabMenu = false },
                    )
                }
            }
        }

        if (cur != null) {
            // ── Terminal output: real terminal emulator, not a text list ──────
            XtermTerminalView(
                tabId = cur.id,
                raw = cur.raw,
                onInput = onInput,
                onResize = { cols, rows -> onResize(cur.id, cols, rows) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

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
                    .background(cs.surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    placeholder = { Text("›", color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace) },
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = cs.onSurface,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = cs.outline,
                        focusedBorderColor = cs.primary,
                        cursorColor = cs.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitInput() }),
                )
                Spacer(Modifier.width(6.dp))
                FilledTonalButton(
                    onClick = { submitInput() },
                    enabled = input.isNotEmpty(),
                    shape = CircleShape,
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
                    Text("Tap to open a terminal", color = cs.onSurfaceVariant)
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
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(cs.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        keys.forEach { (label, payload) ->
            Surface(
                onClick = { onSend(payload) },
                shape = RoundedCornerShape(8.dp),
                color = cs.surfaceVariant,
            ) {
                Text(
                    label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

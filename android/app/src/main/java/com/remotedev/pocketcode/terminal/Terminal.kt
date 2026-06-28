package com.remotedev.pocketcode.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class Tab(val id: String, val title: String, val lines: MutableStateFlow<List<AnnotatedString>>)

// Minimal ANSI 16-color parser. Extend with 256/truecolor when needed.
private val ANSI = mapOf(
    30 to Color(0xFF1E1E1E), 31 to Color(0xFFEF4444), 32 to Color(0xFF22C55E), 33 to Color(0xFFEAB308),
    34 to Color(0xFF3B82F6), 35 to Color(0xFFA855F7), 36 to Color(0xFF06B6D4), 37 to Color(0xFFE5E5E5),
    90 to Color(0xFF6B7280), 91 to Color(0xFFF87171), 92 to Color(0xFF4ADE80), 93 to Color(0xFFFACC15),
    94 to Color(0xFF60A5FA), 95 to Color(0xFFC084FC), 96 to Color(0xFF22D3EE), 97 to Color(0xFFF5F5F5),
)

fun renderAnsi(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0; var cur = Color(0xFFE5E5E5)
    while (i < text.length) {
        if (text[i] == '\u001B' && i + 1 < text.length && text[i + 1] == '[') {
            val end = text.indexOf('m', i + 2).takeIf { it > 0 } ?: text.length
            val codes = text.substring(i + 2, end).split(';').mapNotNull { it.toIntOrNull() }
            for (c in codes) ANSI[c]?.let { cur = it }
            i = end + 1
        } else { pushStyle(SpanStyle(color = cur)); append(text[i]); i++ }
    }
}

@Composable
fun TerminalScreen(onInput: (String) -> Unit) {
    val tabs = remember { mutableStateListOf(Tab("default", "shell", MutableStateFlow(emptyList()))) }
    var active by remember { mutableStateOf(0) }
    val cur = tabs.getOrNull(active) ?: return

    Column(Modifier.fillMaxSize()) {
        // Tab strip
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            tabs.forEachIndexed { i, t ->
                FilterChip(selected = i == active, onClick = { active = i }, label = { Text(t.title) })
                Spacer(Modifier.width(4.dp))
            }
            TextButton(onClick = { tabs.add(Tab("t-${System.currentTimeMillis()}", "shell-${tabs.size}", MutableStateFlow(emptyList()))); active = tabs.size - 1 }) { Text("+") }
        }

        val lines by cur.lines.collectAsState()
        LazyColumn(Modifier.weight(1f).background(Color(0xFF0E0E10)).padding(8.dp)) {
            items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        }

        ExtraKeys(onSend = { onInput(it) })
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

fun appendAnsi(tab: Tab, chunk: String) {
    val rendered = renderAnsi(chunk)
    tab.lines.update { it + rendered }
}

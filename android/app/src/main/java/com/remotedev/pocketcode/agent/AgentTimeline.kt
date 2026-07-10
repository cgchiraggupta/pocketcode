package com.remotedev.pocketcode.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable

@Serializable
data class AgentEvent(val ts: Long, val kind: String, val summary: String, val tab: String = "")

object AgentEventParser {
    private val FILE_CHANGED = Regex("(?:modified|created|wrote|saved|updated)\\s+[`']?([^\\s`']+)")
    private val RAN_CMD      = Regex("\\$\\s+(.+)")   // ponytail: use containsMatchIn, not matches
    private val TEST_RESULT  = Regex("(PASS|FAIL|tests?\\s+passed|✗|✓|\\d+\\s+passed)")
    private val TOOL_USE     = Regex("<(bash|read_file|write_file|edit_file|search)>")

    fun parse(chunk: String, tab: String, sink: (AgentEvent) -> Unit) {
        for (line in chunk.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            when {
                TOOL_USE.containsMatchIn(t)     -> sink(AgentEvent(System.currentTimeMillis(), "tool",         t, tab))
                FILE_CHANGED.containsMatchIn(t) -> sink(AgentEvent(System.currentTimeMillis(), "file_changed", t, tab))
                TEST_RESULT.containsMatchIn(t)  -> sink(AgentEvent(System.currentTimeMillis(), "tests",        t, tab))
                RAN_CMD.containsMatchIn(t)      -> sink(AgentEvent(System.currentTimeMillis(), "cmd",          t, tab))
            }
        }
    }
}

private fun kindGlyph(kind: String) = when (kind) {
    "file_changed" -> "✎"
    "cmd"          -> "\$"
    "tests"        -> "✓"
    "tool"         -> "⚙"
    else           -> "·"
}

private fun kindColor(kind: String) = when (kind) {
    "file_changed" -> Color(0xFF3B82F6)
    "cmd"          -> Color(0xFFEAB308)
    "tests"        -> Color(0xFF22C55E)
    "tool"         -> Color(0xFFA855F7)
    else           -> Color(0xFF6B7280)
}

@Composable
fun AgentTimelineScreen(events: List<AgentEvent>) {
    val listState = rememberLazyListState()

    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.size - 1)
    }

    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No agent activity yet.\nStart a Claude Code session in the terminal.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(events, key = { "${it.ts}-${it.kind}-${it.summary.hashCode()}" }) { e ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = kindGlyph(e.kind),
                    color = kindColor(e.kind),
                    fontSize = 14.sp,
                    modifier = Modifier.width(24.dp).padding(top = 1.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = e.kind.replace('_', ' '),
                        style = MaterialTheme.typography.labelSmall,
                        color = kindColor(e.kind),
                    )
                    Text(
                        text = e.summary,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

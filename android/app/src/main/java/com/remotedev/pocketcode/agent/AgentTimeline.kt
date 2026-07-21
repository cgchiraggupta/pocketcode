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
data class AgentEvent(
    val ts: Long,
    val kind: String,
    val summary: String,
    val tab: String = "",
    /** Stable CLI identifier supplied by the server for native chat events. */
    val agentId: String = "",
)

/**
 * Removes ANSI/VT control sequences from raw PTY text so the timeline heuristics
 * match on the *visible* content, not escape codes. The old parser ran regexes
 * straight over raw bytes and so tagged shell-integration OSC markers
 * (`]697;StartPrompt`), colour codes (`[0m[27m`), and cursor moves (`[31B`) as
 * "events" -- filling the Agent tab with garbage. Strip first, then classify.
 */
object AnsiStripper {
    // CSI: ESC [ params intermediates final(@-~)
    private val CSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    // OSC: ESC ] ... terminated by BEL(07) or ST(ESC \\) -- covers shell-integration 697/133 markers
    private val OSC = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    // Any other escape seq: ESC + one following byte (charset selects, ESC=, ESC>, stray ESC).
    private val OTHER_ESC = Regex("\u001B.?")
    // Remaining C0 control bytes except tab(09)/newline(0A), plus CR(0D) and DEL(7F).
    private val CTRL = Regex("[\u0000-\u0008\u000B-\u001F\u007F]")

    fun strip(text: String): String =
        text.replace(CSI, "")
            .replace(OSC, "")
            .replace(OTHER_ESC, "")
            .replace(CTRL, "")
}

/**
 * High-precision, agent-agnostic activity classifier. Deliberately conservative:
 * a mostly-empty but clean timeline beats a noisy one, so patterns only fire on
 * clearly-structured lines and every match is de-duplicated against the last
 * emitted summary (agents repaint the same line across many stdout chunks).
 */
object AgentEventParser {
    // "Edited src/foo.ts" / "Wrote README.md" / "Created x" -- word must be at line
    // start so prose like "...just updated the plan" doesn't trip it.
    private val FILE_CHANGED = Regex("^(?:Edited|Created|Wrote|Updated|Modified|Deleted|Added)\\s+([^\\s].*)$", RegexOption.IGNORE_CASE)
    // A command echo: line literally begins with "$ " followed by a command.
    private val RAN_CMD      = Regex("^\\$\\s+(\\S.*)$")
    // Test summaries only -- "12 passed", "3 failed", "Tests: 5 passed". Bare
    // ✓/✗ is NOT a signal: the zsh git prompt uses ✗.
    private val TEST_RESULT  = Regex("\\b(\\d+\\s+(?:passed|failed|skipped)|Tests?:\\s*\\d+|PASSED|FAILED)\\b", RegexOption.IGNORE_CASE)

    // Skip lines that are pure box-drawing / punctuation / spinner frames.
    private val NOISE_ONLY = Regex("^[\\s\\p{Punct}│─┌┐└┘├┤┬┴┼╭╮╰╯▏▎▍▌▋▊▉█░▒▓⠀-⣿·•◆◇○●▶▷▸►]+$")

    // De-dupe state: agents repaint, so the same summary streams repeatedly.
    private var lastSummary: String = ""

    fun parse(chunk: String, tab: String, sink: (AgentEvent) -> Unit) {
        for (rawLine in AnsiStripper.strip(chunk).lines()) {
            val t = rawLine.trim()
            if (t.isEmpty() || t.length < 3 || NOISE_ONLY.matches(t)) continue
            val kind = when {
                FILE_CHANGED.containsMatchIn(t) -> "file_changed"
                RAN_CMD.containsMatchIn(t)      -> "cmd"
                TEST_RESULT.containsMatchIn(t)  -> "tests"
                else                            -> continue
            }
            val summary = t.take(200)
            if (summary == lastSummary) continue   // collapse repaint duplicates
            lastSummary = summary
            sink(AgentEvent(System.currentTimeMillis(), kind, summary, tab))
        }
    }
}

// Which approval prompts the user already answered. Process-scoped (not
// `remember`) so answering one and navigating away doesn't resurrect its
// Approve/Reject buttons when the Agent tab is reopened.
private val resolvedApprovals = androidx.compose.runtime.mutableStateListOf<String>()

private fun kindGlyph(kind: String) = when (kind) {
    "file_changed"      -> "✎"
    "cmd"               -> "\$"
    "tests"             -> "✓"
    "tool"              -> "⚙"
    "awaiting_approval" -> "⏸"
    else                -> "·"
}

private fun kindColor(kind: String) = when (kind) {
    "file_changed"      -> Color(0xFF3B82F6)
    "cmd"               -> Color(0xFFEAB308)
    "tests"             -> Color(0xFF22C55E)
    "tool"              -> Color(0xFFA855F7)
    "awaiting_approval" -> Color(0xFFEF4444)
    else                -> Color(0xFF6B7280)
}

@Composable
fun AgentTimelineScreen(
    events: List<AgentEvent>,
    onApprove: (String) -> Unit = {},
    onReject: (String) -> Unit = {},
) {
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
            val eventKey = "${e.ts}-${e.kind}-${e.summary.hashCode()}"
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
                    if (e.kind == "awaiting_approval" && eventKey !in resolvedApprovals) {
                        Row(Modifier.padding(top = 4.dp)) {
                            Button(
                                onClick = { onApprove(e.tab); if (eventKey !in resolvedApprovals) resolvedApprovals.add(eventKey) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) { Text("Approve", fontSize = 12.sp) }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { onReject(e.tab); if (eventKey !in resolvedApprovals) resolvedApprovals.add(eventKey) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) { Text("Reject", fontSize = 12.sp) }
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

package com.remotedev.pocketcode.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remotedev.pocketcode.terminal.Tab
import com.remotedev.pocketcode.terminal.XtermTerminalView

/**
 * Per-terminal native chat surface for structured Claude Code and Codex CLI
 * events. The raw xterm view remains one tap away for unsupported output,
 * debugging, and other agent CLIs.
 */
@Composable
fun AgentChatScreen(
    events: List<AgentEvent>,
    tabs: List<Tab>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onInput: (tabId: String, data: String) -> Unit,
    onResize: (tabId: String, cols: Int, rows: Int) -> Unit,
) {
    val threadTabs = remember(events, tabs) {
        val eventTabs = events.map { it.tab }.filter { it.isNotBlank() }.toSet()
        tabs.filter { it.id in eventTabs }
    }
    var selectedTab by remember { mutableStateOf<String?>(null) }
    var rawTerminal by remember { mutableStateOf(false) }
    LaunchedEffect(threadTabs) {
        if (selectedTab !in threadTabs.map { it.id }) selectedTab = threadTabs.firstOrNull()?.id
    }
    val selected = threadTabs.firstOrNull { it.id == selectedTab }

    Column(Modifier.fillMaxSize()) {
        if (threadTabs.isEmpty()) {
            EmptyAgentChat()
            return@Column
        }
        ThreadSelector(
            tabs = threadTabs,
            selectedTab = selectedTab,
            onSelect = { selectedTab = it; rawTerminal = false },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected?.let { agentLabel(events, it.id) } ?: "Agent",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { rawTerminal = !rawTerminal }) {
                Text(if (rawTerminal) "Native chat" else "Raw terminal")
            }
        }
        if (selected == null) return@Column
        if (rawTerminal) {
            XtermTerminalView(
                tabId = selected.id,
                raw = selected.raw,
                onInput = { onInput(selected.id, it) },
                onResize = { cols, rows -> onResize(selected.id, cols, rows) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            NativeConversation(
                events = events.filter { it.tab == selected.id },
                onApprove = onApprove,
                onReject = onReject,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EmptyAgentChat() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No native agent threads", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Start Claude Code or Codex CLI in a terminal. Other tools remain available in Raw terminal.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ThreadSelector(tabs: List<Tab>, selectedTab: String?, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEach { tab ->
            FilterChip(
                selected = tab.id == selectedTab,
                onClick = { onSelect(tab.id) },
                label = { Text(tab.title, fontFamily = FontFamily.Monospace, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun NativeConversation(
    events: List<AgentEvent>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    modifier: Modifier,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(events.size) { if (events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex) }
    if (events.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Waiting for structured agent activity…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(events, key = { "${it.ts}-${it.kind}-${it.summary.hashCode()}" }) { event ->
            ChatBubble(event, onApprove, onReject)
        }
    }
}

@Composable
private fun ChatBubble(event: AgentEvent, onApprove: (String) -> Unit, onReject: (String) -> Unit) {
    val isQuestion = event.kind == "question" || event.kind == "awaiting_approval"
    val color = when (event.kind) {
        "tool_call" -> Color(0xFF6D28D9)
        "diff" -> Color(0xFF2563EB)
        "question", "awaiting_approval" -> Color(0xFFB45309)
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (isQuestion) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(event.kind.replace('_', ' '), color = color, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                event.summary,
                fontFamily = if (event.kind == "tool_call" || event.kind == "diff") FontFamily.Monospace else FontFamily.Default,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
            if (event.kind == "awaiting_approval") {
                Row(Modifier.padding(top = 8.dp)) {
                    Button(onClick = { onApprove(event.tab) }) { Text("Approve") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onReject(event.tab) }) { Text("Reject") }
                }
            }
        }
    }
}

private fun agentLabel(events: List<AgentEvent>, tabId: String): String = when (
    events.lastOrNull { it.tab == tabId }?.agentId
) {
    "claude-code" -> "Claude Code"
    "codex-cli" -> "Codex CLI"
    else -> "Agent"
}

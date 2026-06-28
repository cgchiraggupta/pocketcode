package com.remotedev.pocketcode.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

@Serializable
data class AgentEvent(val ts: Long, val kind: String, val summary: String)

object AgentEventParser {
    private val FILE_CHANGED = Regex("(?:modified|created|wrote|saved)\\s+[`']?([^\\s`']+)")
    private val RAN_CMD = Regex("\\$\\s+(.+)")              // ponytail: simplest heuristics
    private val TEST_RESULT = Regex("(PASS|FAIL|tests?\\s+passed|✗|✓)")

    fun parse(chunk: String, sink: (AgentEvent) -> Unit) {
        for (line in chunk.lines()) {
            when {
                FILE_CHANGED.containsMatchIn(line) -> sink(AgentEvent(System.currentTimeMillis(), "file_changed", line.trim()))
                TEST_RESULT.containsMatchIn(line) -> sink(AgentEvent(System.currentTimeMillis(), "tests", line.trim()))
                RAN_CMD.matches(line) -> sink(AgentEvent(System.currentTimeMillis(), "cmd", line.trim()))
            }
        }
    }
}

@Composable
fun AgentTimelineScreen(events: MutableStateFlow<List<AgentEvent>>) {
    val list by events.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        items(list) { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(e.kind, modifier = Modifier.width(100.dp), style = MaterialTheme.typography.labelMedium)
                Text(e.summary)
            }
        }
    }
}

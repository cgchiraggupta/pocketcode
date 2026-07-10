package com.remotedev.pocketcode.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Per-tab live agent lifecycle used to drive a single in-place notification
 * (CodeMote-style: running ↔ waiting, not a stack of one-shot posts).
 */
sealed class LiveAgentState {
    /** Agent is producing output / working; no user action needed. */
    data object Running : LiveAgentState()

    /** Agent is blocked on a y/n-style approval prompt. */
    data class Waiting(val snippet: String) : LiveAgentState()

    /** PTY process exited. */
    data class Finished(val code: Int) : LiveAgentState()
}

/**
 * In-memory map of tabId → live state. Pure logic, no Android deps — unit-testable.
 * [apply] returns true when the effective state actually changed (so callers can
 * avoid thrashing NotificationManager with identical re-posts).
 */
object AgentLiveTracker {
    private val _states = MutableStateFlow<Map<String, LiveAgentState>>(emptyMap())
    val states: StateFlow<Map<String, LiveAgentState>> = _states.asStateFlow()

    fun get(tabId: String): LiveAgentState? = _states.value[tabId]

    /**
     * Transition rules:
     * - Waiting is sticky until approve/reject/finish/clear (term.data must not
     *   downgrade Waiting → Running while the prompt is still up).
     * - Running can be set from Idle or re-asserted while already Running.
     * - Finished always wins over Running/Waiting.
     * - force=true bypasses stickiness (used after user taps Approve/Reject).
     */
    fun apply(tabId: String, next: LiveAgentState, force: Boolean = false): Boolean {
        var changed = false
        _states.update { cur ->
            val prev = cur[tabId]
            val effective = when {
                force -> next
                prev is LiveAgentState.Waiting && next is LiveAgentState.Running -> prev
                prev == next -> prev
                prev is LiveAgentState.Waiting && next is LiveAgentState.Waiting &&
                    prev.snippet == next.snippet -> prev
                else -> next
            }
            if (effective == prev) cur
            else {
                changed = true
                cur + (tabId to effective)
            }
        }
        return changed
    }

    fun remove(tabId: String): Boolean {
        var changed = false
        _states.update { cur ->
            if (!cur.containsKey(tabId)) cur
            else {
                changed = true
                cur - tabId
            }
        }
        return changed
    }

    fun clearAll() {
        _states.value = emptyMap()
    }

    /** Human-readable summary for the session foreground notification. */
    fun summary(): String {
        val map = _states.value
        if (map.isEmpty()) return ""
        val waiting = map.values.count { it is LiveAgentState.Waiting }
        val running = map.values.count { it is LiveAgentState.Running }
        val finished = map.values.count { it is LiveAgentState.Finished }
        return buildList {
            if (waiting > 0) add("$waiting waiting")
            if (running > 0) add("$running running")
            if (finished > 0) add("$finished done")
        }.joinToString(" · ")
    }
}

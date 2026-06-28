package com.remotedev.pocketcode.agent

import kotlinx.serialization.Serializable

// Best-effort cost tracker. Matches Claude Code's "X tokens" and Codex "tokens used" lines.
@Serializable data class CostUpdate(val usd: Double, val inputTokens: Long, val outputTokens: Long, val session: String)

object CostTracker {
    private val INPUT = Regex("(\\d[\\d,]*)\\s*input\\s*tokens?", RegexOption.IGNORE_CASE)
    private val OUTPUT = Regex("(\\d[\\d,]*)\\s*output\\s*tokens?", RegexOption.IGNORE_CASE)

    data class State(var input: Long = 0, var output: Long = 0) { fun approxUsd(): Double = (input + output) * 3.0 / 1_000_000 }

    fun consume(chunk: String, st: State): CostUpdate? {
        var changed = false
        INPUT.find(chunk)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()?.let { st.input += it; changed = true }
        OUTPUT.find(chunk)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()?.let { st.output += it; changed = true }
        return if (changed) CostUpdate(st.approxUsd(), st.input, st.output, "current") else null
    }
}

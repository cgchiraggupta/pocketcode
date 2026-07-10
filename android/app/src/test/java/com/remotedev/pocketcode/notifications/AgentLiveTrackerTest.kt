package com.remotedev.pocketcode.notifications

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLiveTrackerTest {

    @After
    fun tearDown() {
        AgentLiveTracker.clearAll()
    }

    @Test
    fun applyRunningFromIdleChanges() {
        assertTrue(AgentLiveTracker.apply("t1", LiveAgentState.Running))
        assertEquals(LiveAgentState.Running, AgentLiveTracker.get("t1"))
    }

    @Test
    fun reassertRunningDoesNotChange() {
        assertTrue(AgentLiveTracker.apply("t1", LiveAgentState.Running))
        assertFalse(AgentLiveTracker.apply("t1", LiveAgentState.Running))
    }

    @Test
    fun waitingIsStickyAgainstRunning() {
        assertTrue(AgentLiveTracker.apply("t1", LiveAgentState.Waiting("Approve?")))
        assertFalse(
            "term.data must not downgrade Waiting → Running",
            AgentLiveTracker.apply("t1", LiveAgentState.Running),
        )
        assertTrue(AgentLiveTracker.get("t1") is LiveAgentState.Waiting)
    }

    @Test
    fun forceBypassesWaitingStickiness() {
        AgentLiveTracker.apply("t1", LiveAgentState.Waiting("Approve?"))
        assertTrue(AgentLiveTracker.apply("t1", LiveAgentState.Running, force = true))
        assertEquals(LiveAgentState.Running, AgentLiveTracker.get("t1"))
    }

    @Test
    fun finishedAlwaysWins() {
        AgentLiveTracker.apply("t1", LiveAgentState.Waiting("x"))
        assertTrue(AgentLiveTracker.apply("t1", LiveAgentState.Finished(0), force = true))
        assertEquals(LiveAgentState.Finished(0), AgentLiveTracker.get("t1"))
    }

    @Test
    fun tabsAreIndependent() {
        AgentLiveTracker.apply("a", LiveAgentState.Running)
        AgentLiveTracker.apply("b", LiveAgentState.Waiting("need y/n"))
        assertEquals(LiveAgentState.Running, AgentLiveTracker.get("a"))
        assertTrue(AgentLiveTracker.get("b") is LiveAgentState.Waiting)
    }

    @Test
    fun summaryCountsStates() {
        AgentLiveTracker.apply("a", LiveAgentState.Running)
        AgentLiveTracker.apply("b", LiveAgentState.Waiting("?"))
        AgentLiveTracker.apply("c", LiveAgentState.Finished(1), force = true)
        val s = AgentLiveTracker.summary()
        assertTrue(s.contains("1 waiting"))
        assertTrue(s.contains("1 running"))
        assertTrue(s.contains("1 done"))
    }

    @Test
    fun removeAndClearAll() {
        AgentLiveTracker.apply("a", LiveAgentState.Running)
        assertTrue(AgentLiveTracker.remove("a"))
        assertFalse(AgentLiveTracker.remove("a"))
        AgentLiveTracker.apply("b", LiveAgentState.Running)
        AgentLiveTracker.clearAll()
        assertEquals(0, AgentLiveTracker.states.value.size)
        assertEquals("", AgentLiveTracker.summary())
    }

    @Test
    fun identicalWaitingSnippetDoesNotRefire() {
        assertTrue(AgentLiveTracker.apply("t1", LiveAgentState.Waiting("same")))
        assertFalse(AgentLiveTracker.apply("t1", LiveAgentState.Waiting("same")))
    }
}

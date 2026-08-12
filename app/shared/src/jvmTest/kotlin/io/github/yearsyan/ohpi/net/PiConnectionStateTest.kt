package io.github.yearsyan.ohpi.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiConnectionStateTest {
    @Test
    fun staleOpenCannotPublishAfterNewAttemptBegins() {
        val state = PiConnectionState()
        val staleGeneration = state.begin()
        val currentGeneration = state.begin()

        assertFalse(state.tryOpen(staleGeneration))
        assertFalse(state.connected)
        assertTrue(state.tryOpen(currentGeneration))
        assertTrue(state.connected)
    }

    @Test
    fun staleTerminalCannotClearNewerConnectedAttempt() {
        val state = PiConnectionState()
        val staleGeneration = state.begin()
        assertTrue(state.tryOpen(staleGeneration))

        val currentGeneration = state.begin()
        assertTrue(state.tryOpen(currentGeneration))
        assertFalse(state.tryTerminate(staleGeneration))

        assertTrue(state.connected)
        assertTrue(state.isConnected(currentGeneration))
    }

    @Test
    fun terminalAttemptCannotReopenOrDeliverOpen() {
        val state = PiConnectionState()
        val generation = state.begin()

        assertTrue(state.tryTerminate(generation))
        assertFalse(state.tryOpen(generation))
        assertFalse(state.isConnected(generation))
        assertFalse(state.connected)
    }

    @Test
    fun disconnectInvalidatesPendingAndConnectedGenerations() {
        val state = PiConnectionState()
        val pendingGeneration = state.begin()
        state.invalidate()
        assertFalse(state.tryOpen(pendingGeneration))

        val connectedGeneration = state.begin()
        assertTrue(state.tryOpen(connectedGeneration))
        state.invalidate()
        assertFalse(state.tryTerminate(connectedGeneration))
        assertFalse(state.connected)
    }
}

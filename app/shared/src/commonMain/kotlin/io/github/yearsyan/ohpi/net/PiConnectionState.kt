package io.github.yearsyan.ohpi.net

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Atomically couples a WebSocket attempt generation with its lifecycle phase. */
@OptIn(ExperimentalAtomicApi::class)
internal class PiConnectionState {
    private val state = AtomicLong(TerminalPhase)

    val connected: Boolean
        get() = phase(state.load()) == OpenPhase

    /** Starts a new pending attempt and returns its generation token. */
    fun begin(): Long = advance(PendingPhase)

    /** Invalidates every token issued so far. */
    fun invalidate() {
        advance(TerminalPhase)
    }

    /** Publishes open only while [generation] is the current pending attempt. */
    fun tryOpen(generation: Long): Boolean =
        state.compareAndSet(generation or PendingPhase, generation or OpenPhase)

    /** Marks the current attempt terminal without affecting a newer generation. */
    fun tryTerminate(generation: Long): Boolean {
        while (true) {
            val current = state.load()
            if (generation(current) != generation || phase(current) == TerminalPhase) return false
            if (state.compareAndSet(current, generation or TerminalPhase)) return true
        }
    }

    fun isCurrent(generation: Long): Boolean = generation(state.load()) == generation

    fun isConnected(generation: Long): Boolean = state.load() == (generation or OpenPhase)

    private fun advance(newPhase: Long): Long {
        while (true) {
            val current = state.load()
            val nextGeneration = (generation(current) + GenerationStep) and GenerationMask
            val next = nextGeneration or newPhase
            if (state.compareAndSet(current, next)) return nextGeneration
        }
    }

    private fun generation(value: Long): Long = value and GenerationMask

    private fun phase(value: Long): Long = value and PhaseMask

    private companion object {
        const val PhaseMask = 3L
        const val GenerationMask = PhaseMask.inv()
        const val GenerationStep = PhaseMask + 1L
        const val PendingPhase = 0L
        const val OpenPhase = 1L
        const val TerminalPhase = 2L
    }
}

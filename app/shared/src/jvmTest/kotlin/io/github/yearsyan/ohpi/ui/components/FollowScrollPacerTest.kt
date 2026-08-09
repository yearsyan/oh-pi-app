package io.github.yearsyan.ohpi.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The pacer must space turns to at most one per window (so streaming deltas
 * do not trigger a scroll per token) without dropping the trailing turn (so
 * the final delta still lands at the bottom).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowScrollPacerTest {
    @Test
    fun secondTurnWaitsOutTheWindow() = runTest {
        val pacer = FollowScrollPacer(100.milliseconds, nowMs = { testScheduler.currentTime })
        pacer.awaitTurn() // the first turn is granted immediately

        var secondGranted = false
        val job = launch {
            pacer.awaitTurn()
            secondGranted = true
        }
        runCurrent()
        assertFalse(secondGranted, "a back-to-back turn must wait out the window")

        advanceTimeBy(99)
        runCurrent()
        assertFalse(secondGranted)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(secondGranted, "the trailing turn must not be dropped")
        job.join()
    }

    @Test
    fun turnAfterAQuietPeriodIsImmediate() = runTest {
        val pacer = FollowScrollPacer(100.milliseconds, nowMs = { testScheduler.currentTime })
        pacer.awaitTurn()
        advanceTimeBy(5_000)
        runCurrent()

        var granted = false
        val job = launch {
            pacer.awaitTurn()
            granted = true
        }
        runCurrent()
        assertTrue(granted, "a turn after the window expired must not wait")
        job.join()
    }
}

package io.github.yearsyan.ohpi.chat

import io.github.yearsyan.ohpi.data.SessionSyncPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionSyncPhaseTest {
    @Test
    fun followsAttachSynchronizationEvents() {
        assertEquals(
            SessionSyncPhase.RestoringHistory,
            sessionSyncPhaseForEvent("history_begin"),
        )
        assertEquals(
            SessionSyncPhase.CatchingUp,
            sessionSyncPhaseForEvent("replay_begin"),
        )
        assertEquals(SessionSyncPhase.Idle, sessionSyncPhaseForEvent("ready"))
    }

    @Test
    fun ignoresEventsThatDoNotChangeThePhase() {
        assertNull(sessionSyncPhaseForEvent("history_end"))
        assertNull(sessionSyncPhaseForEvent("replay_event"))
        assertNull(sessionSyncPhaseForEvent("replay_binary_begin"))
        assertNull(sessionSyncPhaseForEvent("replay_end"))
    }
}

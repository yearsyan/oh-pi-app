package io.github.yearsyan.ohpi.ui.components

import io.github.yearsyan.ohpi.data.ConnState
import io.github.yearsyan.ohpi.data.SessionSyncPhase
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionStatusTest {
    @Test
    fun reportsRestorationPhasesInsteadOfConnecting() {
        assertEquals(
            SessionStatus.RestoringSession,
            resolveSessionStatus(ConnState.Connecting, SessionSyncPhase.RestoringHistory),
        )
        assertEquals(
            SessionStatus.SyncingLatestActivity,
            resolveSessionStatus(ConnState.Connecting, SessionSyncPhase.CatchingUp),
        )
    }

    @Test
    fun followsConnectionStateOutsideRestoration() {
        assertEquals(
            SessionStatus.Connecting,
            resolveSessionStatus(ConnState.Connecting, SessionSyncPhase.Idle),
        )
        assertEquals(
            SessionStatus.Connected,
            resolveSessionStatus(ConnState.Ready, SessionSyncPhase.Idle),
        )
        assertEquals(
            SessionStatus.Disconnected,
            resolveSessionStatus(ConnState.Disconnected, SessionSyncPhase.Idle),
        )
        assertEquals(
            SessionStatus.ConnectionError,
            resolveSessionStatus(ConnState.Error, SessionSyncPhase.Idle),
        )
    }
}

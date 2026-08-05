package io.github.yearsyan.pi.ui.components

import androidx.compose.runtime.Composable
import io.github.yearsyan.pi.data.ConnState
import io.github.yearsyan.pi.data.SessionSyncPhase
import io.github.yearsyan.pi.i18n.S
import kotlin.math.roundToInt

internal enum class SessionStatus {
    Connected,
    Connecting,
    RestoringSession,
    SyncingLatestActivity,
    Disconnected,
    ConnectionError,
}

internal fun resolveSessionStatus(
    conn: ConnState,
    syncPhase: SessionSyncPhase,
): SessionStatus =
    when (syncPhase) {
        SessionSyncPhase.RestoringHistory -> SessionStatus.RestoringSession
        SessionSyncPhase.CatchingUp -> SessionStatus.SyncingLatestActivity
        SessionSyncPhase.Idle -> when (conn) {
            ConnState.Ready -> SessionStatus.Connected
            ConnState.Connecting -> SessionStatus.Connecting
            ConnState.Disconnected -> SessionStatus.Disconnected
            ConnState.Error -> SessionStatus.ConnectionError
        }
    }

@Composable
internal fun SessionStatus.localizedLabel(progress: Float? = null): String {
    val label = when (this) {
        SessionStatus.Connected -> S.connected
        SessionStatus.Connecting -> S.connecting
        SessionStatus.RestoringSession -> S.restoringSession
        SessionStatus.SyncingLatestActivity -> S.syncingLatestActivity
        SessionStatus.Disconnected -> S.disconnected
        SessionStatus.ConnectionError -> S.connectionError
    }
    val determinate = progress?.takeIf {
        this == SessionStatus.RestoringSession || this == SessionStatus.SyncingLatestActivity
    } ?: return label
    return "$label ${(determinate.coerceIn(0f, 1f) * 100f).roundToInt()}%"
}

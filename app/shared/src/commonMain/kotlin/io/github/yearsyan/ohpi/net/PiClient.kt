package io.github.yearsyan.ohpi.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal WebSocket client for ohpi gateways (one connection per instance).
 *
 * The socket machinery runs on [Dispatchers.Default]; listener callbacks are
 * posted back onto the given [scope] (typically a main-thread viewModelScope)
 * so observers never mutate UI state from socket threads.
 */
open class PiClient(private val scope: CoroutineScope) {

    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onBinary(bytes: ByteArray)
        fun onClose(code: Short, reason: String)
        fun onFailure(message: String)
    }

    private val http = HttpClient {
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
    }

    private var job: Job? = null
    private val outbox = Channel<String>(capacity = 64)

    private val connectionState = PiConnectionState()

    open val connected: Boolean
        get() = connectionState.connected

    private fun dispatch(generation: Long, block: () -> Unit) {
        scope.launch {
            if (connectionState.isCurrent(generation)) block()
        }
    }

    /** Delivers replay/history frames with backpressure instead of retaining an
     * unbounded queue of frame strings and main-thread coroutines. */
    private suspend fun dispatchAndAwait(
        generation: Long,
        requireConnected: Boolean = false,
        block: () -> Unit,
    ): Boolean = withContext(scope.coroutineContext.minusKey(Job)) {
        val deliver = if (requireConnected) {
            connectionState.isConnected(generation)
        } else {
            connectionState.isCurrent(generation)
        }
        if (deliver) block()
        deliver
    }

    open fun connect(url: String, listener: Listener) {
        disconnect()
        val generation = connectionState.begin()
        job = scope.launch(Dispatchers.Default) {
            try {
                var terminalReason: CloseReason? = null
                http.webSocket(url) {
                    if (!connectionState.tryOpen(generation)) return@webSocket
                    val opened = dispatchAndAwait(generation, requireConnected = true) {
                        listener.onOpen()
                    }
                    if (!opened) return@webSocket
                    val writer = launch {
                        while (true) {
                            send(Frame.Text(outbox.receive()))
                        }
                    }
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    dispatchAndAwait(generation) { listener.onMessage(text) }
                                }
                                is Frame.Binary -> {
                                    dispatchAndAwait(generation) { listener.onBinary(frame.data) }
                                }
                                is Frame.Close -> break
                                else -> Unit
                            }
                        }
                    } finally {
                        writer.cancel()
                    }
                    terminalReason = closeReason.await()
                }
                if (connectionState.tryTerminate(generation)) {
                    dispatch(generation) {
                        listener.onClose(
                            terminalReason?.code ?: CloseReason.Codes.NORMAL.code,
                            terminalReason?.message ?: "",
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                if (connectionState.tryTerminate(generation)) {
                    dispatch(generation) {
                        listener.onFailure(t.message ?: t::class.simpleName ?: "error")
                    }
                }
            }
        }
    }

    open fun send(text: String): Boolean = connected && outbox.trySend(text).isSuccess

    open fun disconnect() {
        connectionState.invalidate()
        job?.cancel()
        job = null
        while (outbox.tryReceive().isSuccess) Unit
    }
}

package io.github.yearsyan.pi.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Minimal WebSocket client for pi2ws gateways (one connection per instance).
 *
 * The socket machinery runs on [Dispatchers.Default]; listener callbacks are
 * posted back onto the given [scope] (typically a main-thread viewModelScope)
 * so observers never mutate UI state from socket threads.
 */
class PiClient(private val scope: CoroutineScope) {

    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onClose(code: Short, reason: String)
        fun onFailure(message: String)
    }

    private val http = HttpClient(CIO) {
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
    }

    private var job: Job? = null
    private val outbox = Channel<String>(capacity = 64)

    @Volatile
    private var connectionGeneration = 0L

    @Volatile
    var connected: Boolean = false
        private set

    private fun dispatch(generation: Long, block: () -> Unit) {
        scope.launch {
            if (connectionGeneration == generation) block()
        }
    }

    fun connect(url: String, listener: Listener) {
        disconnect()
        val generation = ++connectionGeneration
        job = scope.launch(Dispatchers.Default) {
            try {
                var terminalReason: CloseReason? = null
                http.webSocket(url) {
                    if (connectionGeneration != generation) return@webSocket
                    connected = true
                    dispatch(generation) { listener.onOpen() }
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
                                    dispatch(generation) { listener.onMessage(text) }
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
                if (connectionGeneration == generation) connected = false
                dispatch(generation) {
                    listener.onClose(
                        terminalReason?.code ?: CloseReason.Codes.NORMAL.code,
                        terminalReason?.message ?: "",
                    )
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                if (connectionGeneration == generation) connected = false
                dispatch(generation) { listener.onFailure(t.message ?: t::class.simpleName ?: "error") }
            }
        }
    }

    fun send(text: String): Boolean = connected && outbox.trySend(text).isSuccess

    fun disconnect() {
        connectionGeneration++
        connected = false
        job?.cancel()
        job = null
        while (outbox.tryReceive().isSuccess) Unit
    }
}

package io.github.yearsyan.ohpi.chat

import io.github.yearsyan.ohpi.data.ConnState
import io.github.yearsyan.ohpi.net.GatewayCapabilities
import io.github.yearsyan.ohpi.net.PiClient
import io.github.yearsyan.ohpi.net.PiJson
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChatControllerReconnectWatchdogTest {
    @Test
    fun attachOpenTimeoutEntersExistingRetryPath() {
        val fixture = fixture(openTimeoutMillis = 40)
        try {
            fixture.controller.connect("attach", "watchdog-attach")
            waitUntil { fixture.client.connectCount > 0 }
            val disconnectsAfterConnect = fixture.client.disconnectCount

            waitUntil { fixture.client.disconnectCount > disconnectsAfterConnect }

            assertEquals(ConnState.Connecting, fixture.controller.conn)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun createOpenTimeoutStopsWithoutAutomaticRetry() {
        val fixture = fixture(openTimeoutMillis = 40)
        try {
            fixture.controller.connect(
                action = "create",
                sessionId = null,
                workspaceId = "workspace",
                workspaceDirectory = "/workspace",
            )
            waitUntil { fixture.client.connectCount > 0 }

            waitUntil { fixture.controller.conn == ConnState.Error }
            Thread.sleep(80)

            assertEquals(1, fixture.client.connectCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun preReadyProgressRefreshesIdleWatchdogAndReadyCancelsIt() {
        val fixture = fixture(openTimeoutMillis = 1_000, idleTimeoutMillis = 500)
        try {
            fixture.controller.connect("attach", "watchdog-progress")
            waitUntil { fixture.client.connectCount > 0 }
            fixture.client.open()
            val disconnectsAfterOpen = fixture.client.disconnectCount

            repeat(6) {
                Thread.sleep(100)
                fixture.client.message("""{"type":"ohpi","event":"sync_progress"}""")
            }
            fixture.client.message(
                """{"type":"ohpi","event":"ready","action":"attach","session_id":"watchdog-progress","workspace_id":"workspace","workspace_directory":"/workspace"}""",
            )
            waitUntil { fixture.controller.conn == ConnState.Ready }
            Thread.sleep(600)

            assertEquals(disconnectsAfterOpen, fixture.client.disconnectCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun staleGenerationProgressCannotKeepNewAttemptAlive() {
        val fixture = fixture(openTimeoutMillis = 120, idleTimeoutMillis = 500)
        try {
            fixture.controller.connect("attach", "watchdog-generation")
            waitUntil { fixture.client.connectCount == 1 }
            val staleListener = fixture.client.listeners.single()

            fixture.controller.reconnect()
            waitUntil { fixture.client.connectCount == 2 }
            val disconnectsAfterReconnect = fixture.client.disconnectCount
            repeat(8) {
                Thread.sleep(20)
                staleListener.onOpen()
                staleListener.onMessage("""{"type":"ohpi","event":"sync_progress"}""")
            }

            // The current attempt's original 120 ms deadline has elapsed. If stale
            // callbacks reset it, the 500 ms idle deadline would still be pending.
            waitUntil(timeoutMillis = 100) {
                fixture.client.disconnectCount > disconnectsAfterReconnect
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun inactiveCallbacksDoNotRearmWatchdog() {
        val fixture = fixture(openTimeoutMillis = 1_000, idleTimeoutMillis = 80)
        try {
            fixture.controller.connect("attach", "watchdog-inactive")
            waitUntil { fixture.client.connectCount == 1 }
            val disconnectsBeforeInactive = fixture.client.disconnectCount

            fixture.controller.onAppInactive()
            fixture.client.open()
            fixture.client.message("""{"type":"ohpi","event":"sync_progress"}""")
            Thread.sleep(120)

            assertEquals(disconnectsBeforeInactive, fixture.client.disconnectCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun resumeRestartsConnectingAttachButNotUnacknowledgedCreate() {
        val attach = fixture(openTimeoutMillis = 1_000)
        try {
            attach.controller.connect("attach", "resume-connecting")
            waitUntil { attach.client.connectCount == 1 }
            attach.controller.onAppInactive()
            attach.controller.recoverAfterAppResume()
            waitUntil { attach.client.connectCount == 2 }
        } finally {
            attach.close()
        }

        val create = fixture(openTimeoutMillis = 1_000)
        try {
            create.controller.prepareCreate("workspace", "/workspace")
            waitUntil { !create.controller.capabilitiesLoading }
            assertTrue(create.controller.sendPrompt("hello") != null)
            waitUntil { create.client.connectCount == 1 }

            create.controller.onAppInactive()
            create.controller.recoverAfterAppResume()
            Thread.sleep(80)

            assertEquals(1, create.client.connectCount)
        } finally {
            create.close()
        }
    }

    @Test
    fun matchingResumeProbeResponseKeepsSocketAndMismatchedResponseReconnects() {
        val fixture = fixture(
            openTimeoutMillis = 200,
            idleTimeoutMillis = 200,
            resumeTimeoutMillis = 50,
        )
        try {
            fixture.controller.connect("attach", "resume-probe")
            waitUntil { fixture.client.connectCount > 0 }
            fixture.client.open()
            fixture.client.message(
                """{"type":"ohpi","event":"ready","action":"attach","session_id":"resume-probe","workspace_id":"workspace","workspace_directory":"/workspace"}""",
            )
            waitUntil { fixture.controller.conn == ConnState.Ready }
            val initialConnections = fixture.client.connectCount

            fixture.controller.recoverAfterAppResume()
            val firstProbe = waitForProbe(fixture.client)
            fixture.client.message(
                """{"type":"response","id":"$firstProbe","command":"get_state","success":true,"data":{}}""",
            )
            Thread.sleep(80)
            assertEquals(initialConnections, fixture.client.connectCount)

            fixture.controller.recoverAfterAppResume()
            waitUntil { fixture.client.sentMessages.count(::isResumeProbe) >= 2 }
            fixture.client.message(
                """{"type":"response","id":"other-probe","command":"get_state","success":true,"data":{}}""",
            )
            waitUntil { fixture.client.connectCount > initialConnections }
            assertTrue(fixture.controller.conn == ConnState.Connecting)
        } finally {
            fixture.close()
        }
    }

    private fun fixture(
        openTimeoutMillis: Long,
        idleTimeoutMillis: Long = 100,
        resumeTimeoutMillis: Long = 50,
    ): Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = FakePiClient(scope)
        val controller = ChatController(
            scope = scope,
            gateway = "ws://localhost",
            token = "",
            onToast = { _, _ -> },
            onSessionReady = { _, _, _, _ -> },
            onSessionNameChanged = { _, _ -> },
            onStreamingChanged = { _, _ -> },
            strings = {
                ChatController.ChatStrings(
                    commandRejected = "",
                    abortSent = "",
                    compacting = "",
                    compacted = "",
                    retryOk = "",
                    retryFailed = "",
                    agentDone = "",
                    retrying = "",
                    notify = "",
                    modelOptionsFailed = { "" },
                )
            },
            loadCapabilities = {
                GatewayCapabilities(
                    workspaceId = it,
                    directory = "/$it",
                    models = emptyList(),
                )
            },
            client = client,
            webSocketOpenTimeoutMillis = openTimeoutMillis,
            preReadyIdleTimeoutMillis = idleTimeoutMillis,
            resumeProbeTimeoutMillis = resumeTimeoutMillis,
        )
        return Fixture(scope, client, controller)
    }

    private fun waitForProbe(client: FakePiClient): String {
        waitUntil { client.sentMessages.any(::isResumeProbe) }
        val message = client.sentMessages.last(::isResumeProbe)
        return PiJson.parseToJsonElement(message).jsonObject.getValue("id").jsonPrimitive.content
    }

    private fun isResumeProbe(message: String): Boolean =
        message.contains("\"id\":\"ohpi-resume-")

    private fun waitUntil(timeoutMillis: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertTrue(condition(), "condition was not met within ${timeoutMillis}ms")
    }

    private data class Fixture(
        val scope: CoroutineScope,
        val client: FakePiClient,
        val controller: ChatController,
    ) {
        fun close() {
            controller.disconnect()
            scope.cancel()
        }
    }

    private class FakePiClient(scope: CoroutineScope) : PiClient(scope) {
        @Volatile
        var connectCount = 0
            private set

        @Volatile
        var disconnectCount = 0
            private set

        @Volatile
        private var fakeConnected = false

        @Volatile
        private var listener: Listener? = null

        val sentMessages: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val listeners: MutableList<Listener> = Collections.synchronizedList(mutableListOf())

        override val connected: Boolean
            get() = fakeConnected

        override fun connect(url: String, listener: Listener) {
            fakeConnected = false
            this.listener = listener
            listeners += listener
            connectCount++
        }

        override fun send(text: String): Boolean {
            if (!fakeConnected) return false
            sentMessages += text
            return true
        }

        override fun disconnect() {
            disconnectCount++
            fakeConnected = false
        }

        fun open() {
            fakeConnected = true
            listener?.onOpen()
        }

        fun message(text: String) {
            listener?.onMessage(text)
        }
    }
}

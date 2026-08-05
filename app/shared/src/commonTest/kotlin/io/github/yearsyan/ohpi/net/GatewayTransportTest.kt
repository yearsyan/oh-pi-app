package io.github.yearsyan.ohpi.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GatewayTransportTest {
    @Test
    fun targetUsesAddressVisibleFromSshServer() {
        val target = gatewayTarget("http://[::1]:8080/base/", requirePlaintext = true)

        assertEquals("ws://[::1]:8080/base", target.normalizedUrl)
        assertEquals("::1", target.remoteHost)
        assertEquals(8080, target.remotePort)
        assertEquals(
            "ws://127.0.0.1:45678/base",
            loopbackGatewayUrl(target, 45678),
        )
    }

    @Test
    fun sshModeRejectsTlsGatewayBecauseLoopbackWouldBreakHostnameVerification() {
        val failure =
            assertFailsWith<GatewayConnectionException> {
                gatewayTarget("https://gateway.example:8443", requirePlaintext = true)
            }

        assertFalse(failure.retryable)
    }
}

package io.github.yearsyan.ohpi.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GatewayAddressTest {
    @Test
    fun parsesLegacyHttpAddress() {
        val address = assertNotNull(parseGatewayAddress("http://192.168.9.138:18080"))

        assertEquals("192.168.9.138", address.host)
        assertEquals(18080, address.port)
        assertEquals(false, address.tls)
    }

    @Test
    fun derivesTlsFromSecureSchemes() {
        val address = assertNotNull(parseGatewayAddress("https://gateway.example.com"))

        assertEquals("gateway.example.com", address.host)
        assertEquals(443, address.port)
        assertEquals(true, address.tls)
        assertEquals("gateway.example.com:443 · TLS", gatewayAddressLabel("wss://gateway.example.com"))
    }

    @Test
    fun formatsIpv6Address() {
        assertEquals("wss://[2001:db8::1]:9443", buildGatewayUrl("2001:db8::1", 9443, tls = true))
        assertEquals("[2001:db8::1]:9443 · TLS", gatewayAddressLabel("wss://[2001:db8::1]:9443"))
    }

    @Test
    fun includesInitialModelOptionsOnlyWhenCreating() {
        assertEquals(
            "ws://gateway.test:8080/ws?action=create&workspace_id=workspace-1&model=router%2Fvendor%2Fmodel&thinking=xhigh&token=token",
            buildWsUrl(
                base = "ws://gateway.test:8080",
                token = "token",
                action = "create",
                sessionId = null,
                workspaceId = "workspace-1",
                initialModel = "router/vendor/model",
                initialThinking = "xhigh",
            ),
        )
        assertEquals(
            "ws://gateway.test:8080/ws?action=attach&session_id=session&entry_since=entry-42&replay_cursor=1&replay_base=40&replay_since=47&token=token",
            buildWsUrl(
                base = "ws://gateway.test:8080",
                token = "token",
                action = "attach",
                sessionId = "session",
                initialModel = "ignored/model",
                initialThinking = "high",
                entrySince = "entry-42",
                replayCursor = true,
                replayBase = 40,
                replaySince = 47,
            ),
        )
    }
}

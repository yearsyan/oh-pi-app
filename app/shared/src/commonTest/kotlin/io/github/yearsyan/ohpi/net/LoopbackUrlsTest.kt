package io.github.yearsyan.ohpi.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoopbackUrlsTest {

    @Test
    fun loopbackTargetParsesHostAndPort() {
        assertEquals("localhost" to 3000, loopbackUrlTarget("http://localhost:3000/app"))
        assertEquals("127.0.0.1" to 8080, loopbackUrlTarget("http://127.0.0.1:8080"))
        assertEquals("::1" to 9000, loopbackUrlTarget("http://[::1]:9000/"))
        assertEquals("app.localhost" to 5173, loopbackUrlTarget("https://app.localhost:5173"))
    }

    @Test
    fun loopbackTargetUsesSchemeDefaultPort() {
        assertEquals("localhost" to 80, loopbackUrlTarget("http://localhost/status"))
        assertEquals("localhost" to 443, loopbackUrlTarget("https://localhost/status"))
    }

    @Test
    fun loopbackTargetRejectsNonLoopbackOrNonHttp() {
        assertNull(loopbackUrlTarget("http://192.168.1.10:3000"))
        assertNull(loopbackUrlTarget("https://example.com"))
        assertNull(loopbackUrlTarget("ws://localhost:8080"))
        assertNull(loopbackUrlTarget("not a url"))
        assertNull(loopbackUrlTarget(""))
    }

    @Test
    fun rewriteToLocalPortKeepsPathAndQuery() {
        assertEquals(
            "http://127.0.0.1:51234/app?x=1",
            rewriteLoopbackUrl("http://localhost:3000/app?x=1", 51234),
        )
        assertEquals(
            "https://127.0.0.1:4000/",
            rewriteLoopbackUrl("https://[::1]:8443/", 4000),
        )
    }

    @Test
    fun rewriteToGatewayHostKeepsPortAndPath() {
        assertEquals(
            "http://gateway.example:3000/app",
            rewriteLoopbackUrlToGatewayHost("http://localhost:3000/app", "gateway.example"),
        )
    }

    @Test
    fun loopbackHostNameMatching() {
        listOf("localhost", "LOCALHOST", "127.0.0.1", "::1", "[::1]", "ui.localhost")
            .forEach { assert(isLoopbackHostName(it)) { "$it should be loopback" } }
        listOf("example.com", "192.168.0.1", "localhost.example", "")
            .forEach { assert(!isLoopbackHostName(it)) { "$it should not be loopback" } }
    }
}

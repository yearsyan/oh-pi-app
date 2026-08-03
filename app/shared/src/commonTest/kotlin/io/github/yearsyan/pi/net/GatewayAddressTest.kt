package io.github.yearsyan.pi.net

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
}

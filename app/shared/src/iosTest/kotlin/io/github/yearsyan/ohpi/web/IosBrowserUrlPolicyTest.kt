@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yearsyan.ohpi.web

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import platform.Foundation.NSURL

class IosBrowserUrlPolicyTest {
    @Test
    fun loopbackHttpUrlsStayInApp() {
        listOf(
            "http://localhost:3000",
            "https://app.localhost/path",
            "http://localhost.:8080",
            "http://127.0.0.1",
            "http://127.42.0.9:5173",
            "http://[::1]:9000",
            "http://[0:0:0:0:0:0:0:1]",
            "http://[::ffff:127.0.0.1]",
        ).forEach { raw ->
            assertTrue(isIosInAppBrowserUrl(url(raw)), raw)
        }
    }

    @Test
    fun nonLoopbackOrNonHttpUrlsLeaveTheApp() {
        listOf(
            "https://example.com",
            "https://github.com/yearsyan/oh-pi-app",
            "https://localhost.example.com",
            "https://localhost@example.com",
            "http://126.255.255.255",
            "http://128.0.0.1",
            "http://192.168.1.10",
            "ftp://localhost/file",
            "mailto:developer@example.com",
        ).forEach { raw ->
            assertFalse(isIosInAppBrowserUrl(url(raw)), raw)
        }
    }

    private fun url(raw: String): NSURL = assertNotNull(NSURL.URLWithString(raw), raw)
}

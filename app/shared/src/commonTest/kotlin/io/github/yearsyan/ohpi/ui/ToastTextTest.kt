package io.github.yearsyan.ohpi.ui

import io.github.yearsyan.ohpi.i18n.EnStrings
import io.github.yearsyan.ohpi.i18n.ZhStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToastTextTest {
    @Test
    fun iosSocketDiagnosticBecomesLocalizedConnectionMessage() {
        val raw =
            "Exception in http request: Error Domain=NSPOSIXErrorDomain Code=57 " +
                "\"Socket is not connected\" UserInfo={NSErrorFailingURLStringKey=" +
                "ws://127.0.0.1:51746/ws?action=attach&token=very-secret-token}"

        assertEquals(EnStrings.connectionInterruptedError, localizedErrorToast(raw, EnStrings))
        assertEquals(ZhStrings.connectionInterruptedError, localizedErrorToast(raw, ZhStrings))
    }

    @Test
    fun offlineTimeoutAndAuthenticationErrorsUseLocalizedMessages() {
        assertEquals(
            ZhStrings.networkOfflineError,
            localizedErrorToast(
                "Error Domain=NSURLErrorDomain Code=-1009 The Internet connection appears to be offline.",
                ZhStrings,
            ),
        )
        assertEquals(
            EnStrings.requestTimedOutError,
            localizedErrorToast("java.net.SocketTimeoutException: timed out", EnStrings),
        )
        assertEquals(
            ZhStrings.authenticationFailedError,
            localizedErrorToast("401 unauthorized: invalid token", ZhStrings),
        )
    }

    @Test
    fun providerHttpMessageRemainsActionable() {
        assertEquals(
            "429: Overloaded",
            localizedErrorToast(
                """429 {"error":{"message":"Overloaded"}}""",
                EnStrings,
            ),
        )
    }

    @Test
    fun fallbackTextIsSingleLineRedactedAndBounded() {
        val raw =
            "Upload failed\n  at wss://example.test/ws?session=x&token=url-secret " +
                "api_key=body-secret ${"detail ".repeat(40)}"

        val result = localizedErrorToast(raw, EnStrings)

        assertFalse('\n' in result)
        assertFalse("url-secret" in result)
        assertFalse("body-secret" in result)
        assertTrue("wss://example.test/ws" in result)
        assertTrue(result.length <= 160)
        assertTrue(result.endsWith('…'))
    }

    @Test
    fun emptyAndUnknownDiagnosticsUseLocalizedFallback() {
        assertEquals(EnStrings.unknownError, localizedErrorToast("", EnStrings))
        assertEquals(ZhStrings.unknownError, localizedErrorToast("unknown error", ZhStrings))
    }
}

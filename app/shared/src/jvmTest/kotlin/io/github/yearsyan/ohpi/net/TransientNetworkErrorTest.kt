package io.github.yearsyan.ohpi.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransientNetworkErrorTest {

    @Test
    fun iosConnectionAbortIsTransient() {
        val message =
            "Exception in http request: Error Domain=NSPOSIXErrorDomain Code=53 " +
                "\"Software caused connection abort\" UserInfo={NSDescription=Software caused connection abort}"
        assertTrue(isTransientNetworkError(message))
    }

    @Test
    fun iosConnectionResetIsTransient() {
        val message =
            "Exception in http request: Error Domain=NSPOSIXErrorDomain Code=54 " +
                "\"Connection reset by peer\""
        assertTrue(isTransientNetworkError(message))
    }

    @Test
    fun iosBrokenPipeIsTransient() {
        val message = "Error Domain=NSPOSIXErrorDomain Code=32 \"Broken pipe\""
        assertTrue(isTransientNetworkError(message))
    }

    @Test
    fun iosLostAndOfflineAreTransient() {
        assertTrue(
            isTransientNetworkError(
                "Error Domain=NSURLErrorDomain Code=-1005 \"The network connection was lost.\"",
            ),
        )
        assertTrue(
            isTransientNetworkError(
                "Error Domain=NSURLErrorDomain Code=-1009 " +
                    "\"The Internet connection appears to be offline.\"",
            ),
        )
    }

    @Test
    fun androidSocketErrorsAreTransient() {
        assertTrue(isTransientNetworkError("Software caused connection abort"))
        assertTrue(isTransientNetworkError("Connection reset by peer"))
        assertTrue(isTransientNetworkError("Socket closed"))
    }

    @Test
    fun serverErrorsAreNotTransient() {
        assertFalse(isTransientNetworkError("Connection refused"))
        assertFalse(isTransientNetworkError("unauthorized: invalid token"))
        assertFalse(isTransientNetworkError("SSH host key was not trusted"))
        assertFalse(isTransientNetworkError("could not synchronize session"))
        assertFalse(isTransientNetworkError(""))
    }
}

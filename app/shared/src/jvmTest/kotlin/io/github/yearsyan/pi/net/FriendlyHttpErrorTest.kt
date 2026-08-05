package io.github.yearsyan.pi.net

import kotlin.test.Test
import kotlin.test.assertEquals

class FriendlyHttpErrorTest {

    @Test
    fun anthropicStyleErrorExtractsMessage() {
        val raw = """429 {"error":{"type":"rate_limit_error","message":"The engine is currently overloaded, please try again"}}"""
        assertEquals(
            "429: The engine is currently overloaded, please try again",
            friendlyHttpError(raw),
        )
    }

    @Test
    fun anthropicWrappedErrorExtractsMessage() {
        val raw = """429 {"type":"error","error":{"type":"rate_limit_error","message":"Overloaded"}}"""
        assertEquals("429: Overloaded", friendlyHttpError(raw))
    }

    @Test
    fun topLevelMessageIsUsed() {
        val raw = """500 {"message":"boom"}"""
        assertEquals("500: boom", friendlyHttpError(raw))
    }

    @Test
    fun multiLinePrettyPrintedBodyIsParsed() {
        val raw = "429 {\n  \"error\": {\n    \"message\": \"slow down\"\n  }\n}"
        assertEquals("429: slow down", friendlyHttpError(raw))
    }

    @Test
    fun nonHttpErrorTextPassesThroughTrimmed() {
        assertEquals("Connection reset by peer", friendlyHttpError("  Connection reset by peer  "))
    }

    @Test
    fun unparseableBodyFallsBackToRaw() {
        val raw = "429 {not valid json"
        assertEquals(raw, friendlyHttpError(raw))
    }

    @Test
    fun jsonBodyWithoutMessageFallsBackToRaw() {
        val raw = """503 {"error":{"type":"overloaded_error"}}"""
        assertEquals(raw, friendlyHttpError(raw))
    }
}

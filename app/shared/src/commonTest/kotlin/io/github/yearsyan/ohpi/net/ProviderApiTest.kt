package io.github.yearsyan.ohpi.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderApiTest {
    @Test
    fun providerAuthUrlEncodesEveryCredentialParameter() {
        assertEquals(
            "wss://gateway.example/api/provider-auth?provider_id=openai-codex&auth_type=oauth&token=a%20b",
            buildProviderAuthWsUrl("https://gateway.example/", "a b", "openai-codex", "oauth"),
        )
    }

    @Test
    fun parsesProviderPromptsAndRejectsOtherWebSocketTraffic() {
        val event = assertNotNull(
            parseProviderAuthEvent(
                """{"type":"ohpi_provider","event":"prompt","id":"p1","kind":"secret","message":"API key","options":[]}""",
            ),
        )
        assertEquals("p1", event.id)
        assertEquals("secret", event.kind)
        assertNull(parseProviderAuthEvent("""{"type":"response","event":"prompt"}"""))
    }

    @Test
    fun responsesUseOnlyTheExtensionDialogEnvelope() {
        assertTrue(providerAuthInputResponse("p1", "secret").contains("extension_ui_response"))
        assertTrue(providerAuthCancelResponse("p1").contains("\"cancelled\":true"))
    }
}

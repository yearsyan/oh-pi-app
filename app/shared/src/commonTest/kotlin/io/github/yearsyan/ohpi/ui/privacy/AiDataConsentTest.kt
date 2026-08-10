package io.github.yearsyan.ohpi.ui.privacy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AiDataConsentTest {
    @Test
    fun consentKeySeparatesServerAndProviderBoundaries() {
        assertNotEquals(
            aiDataConsentKey(serverId = "ab", providerId = "c"),
            aiDataConsentKey(serverId = "a", providerId = "bc"),
        )
    }

    @Test
    fun consentKeyNormalizesProviderAndChangesWithVersion() {
        assertEquals(
            aiDataConsentKey(serverId = "server", providerId = " OpenAI "),
            aiDataConsentKey(serverId = "server", providerId = "openai"),
        )
        assertNotEquals(
            aiDataConsentKey(serverId = "server", providerId = "openai", version = 1),
            aiDataConsentKey(serverId = "server", providerId = "openai", version = 2),
        )
    }
}

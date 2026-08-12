package io.github.yearsyan.ohpi.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GatewayRuntimeConfigTest {
    @Test
    fun decodesRuntimeConfigurationResponse() {
        val config = PiJson.decodeFromString<GatewayRuntimeConfig>(
            """{
                "title_model":"active",
                "pi_env_file":"~/.zshrc",
                "pi_env_shell":"/bin/zsh",
                "scheduled_session_retention_seconds":259200,
                "restart_required":true,
                "restart_supported":true
            }""".trimIndent(),
        )

        assertEquals("active", config.titleModel)
        assertEquals("~/.zshrc", config.piEnvironmentFile)
        assertEquals("/bin/zsh", config.piEnvironmentShell)
        assertEquals(259_200L, config.scheduledSessionRetentionSeconds)
        assertTrue(config.restartRequired)
        assertTrue(config.restartSupported)
    }

    @Test
    fun defaultsScheduledSessionRetentionForOlderGatewayResponses() {
        val config = PiJson.decodeFromString<GatewayRuntimeConfig>(
            """{"title_model":"auto"}""",
        )

        assertEquals(DEFAULT_SCHEDULED_SESSION_RETENTION_SECONDS, config.scheduledSessionRetentionSeconds)
    }
}

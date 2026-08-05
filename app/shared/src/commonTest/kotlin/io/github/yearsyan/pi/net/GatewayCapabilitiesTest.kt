package io.github.yearsyan.pi.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GatewayCapabilitiesTest {
    @Test
    fun decodesModelSpecificThinkingLevels() {
        val capabilities = PiJson.decodeFromString<GatewayCapabilities>(
            """
            {
              "work_dir": "/workspace",
              "default": {
                "provider": "router",
                "model_id": "vendor/model",
                "thinking_level": "high"
              },
              "models": [{
                "id": "vendor/model",
                "name": "Model",
                "provider": "router",
                "thinking_levels": ["off", "low", "high"]
              }],
              "commands": [{
                "name": "skill:review",
                "description": "Review changed code",
                "source": "skill"
              }]
            }
            """.trimIndent(),
        )

        assertEquals("/workspace", capabilities.workDir)
        assertEquals("vendor/model", assertNotNull(capabilities.defaultSelection).modelId)
        assertEquals(listOf("off", "low", "high"), capabilities.models.single().thinkingLevels)
        assertEquals("skill:review", capabilities.commands.single().name)
        assertEquals("skill", capabilities.commands.single().source)
    }
}

package io.github.yearsyan.ohpi.chat

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PromptImageTest {
    @Test
    fun promptCommandSerializesImageForPiRpc() {
        val command =
            buildPromptCommand(
                sourceId = "app-request-1",
                text = "  inspect this  ",
                images = listOf(PromptImage(data = "aGVsbG8=", mimeType = "image/png", name = "screen.png")),
                isStreaming = false,
            )

        assertEquals("app-request-1", command.getValue("id").jsonPrimitive.content)
        assertEquals("prompt", command.getValue("type").jsonPrimitive.content)
        assertEquals("inspect this", command.getValue("message").jsonPrimitive.content)
        val image = command.getValue("images").jsonArray.single().jsonObject
        assertEquals("image", image.getValue("type").jsonPrimitive.content)
        assertEquals("aGVsbG8=", image.getValue("data").jsonPrimitive.content)
        assertEquals("image/png", image.getValue("mimeType").jsonPrimitive.content)
        assertFalse("name" in image)
    }

    @Test
    fun streamingPromptUsesSteerAndOmitsEmptyImageArray() {
        val command = buildPromptCommand("app-request-2", "next", emptyList(), isStreaming = true)

        assertEquals("steer", command.getValue("streamingBehavior").jsonPrimitive.content)
        assertFalse("images" in command)
    }

    @Test
    fun slashPromptCanBeConfirmedByItsRpcResponse() {
        val command =
            buildPromptCommand(
                sourceId = "app-request-3",
                text = "/skill:review changes",
                images = emptyList(),
                isStreaming = false,
                confirmOnResponse = true,
            )

        assertEquals(true, command.getValue("ohpi_confirm_on_response").jsonPrimitive.boolean)
    }
}

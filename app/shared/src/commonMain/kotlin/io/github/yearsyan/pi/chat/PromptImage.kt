package io.github.yearsyan.pi.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** An image attachment accepted by pi's RPC prompt command. */
data class PromptImage(
    val data: String,
    val mimeType: String,
    val name: String,
)

internal fun buildPromptCommand(
    text: String,
    images: List<PromptImage>,
    isStreaming: Boolean,
): JsonObject =
    buildJsonObject {
        put("type", "prompt")
        put("message", text.trim())
        if (images.isNotEmpty()) {
            putJsonArray("images") {
                images.forEach { image ->
                    addJsonObject {
                        put("type", "image")
                        put("data", image.data)
                        put("mimeType", image.mimeType)
                    }
                }
            }
        }
        if (isStreaming) put("streamingBehavior", "steer")
    }

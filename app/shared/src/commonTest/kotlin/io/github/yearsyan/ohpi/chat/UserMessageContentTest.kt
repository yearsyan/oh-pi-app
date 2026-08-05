package io.github.yearsyan.ohpi.chat

import io.github.yearsyan.ohpi.net.PiJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserMessageContentTest {
    @Test
    fun imageIsRetainedSeparatelyFromBubbleText() {
        val content =
            userMessageContent(
                PiJson.parseToJsonElement(
                    """[
                        {"type":"text","text":"inspect this"},
                        {"type":"image","data":"aGVsbG8=","mimeType":"image/png"}
                    ]""".trimIndent(),
                ),
            )

        assertEquals("inspect this", content.text)
        assertEquals(1, content.images.size)
        assertEquals("aGVsbG8=", content.images.single().data)
        assertEquals("image/png", content.images.single().mimeType)
    }

    @Test
    fun imageOnlyMessageDoesNotCreateSyntheticBubbleText() {
        val content =
            userMessageContent(
                PiJson.parseToJsonElement(
                    """[{"type":"image","data":"aGVsbG8=","mimeType":"image/jpeg"}]""",
                ),
            )

        assertTrue(content.text.isEmpty())
        assertEquals(1, content.images.size)
    }

    @Test
    fun provisionalTitleUsesFirstNonBlankLine() {
        assertEquals("修复会话标题延迟", provisionalSessionTitle("\n  修复会话标题延迟  \n更多内容"))
        assertNull(provisionalSessionTitle(" \n\t"))
    }

    @Test
    fun provisionalTitleIsBounded() {
        val source = "a".repeat(40)
        assertEquals("a".repeat(30) + "…", provisionalSessionTitle(source))
    }
}

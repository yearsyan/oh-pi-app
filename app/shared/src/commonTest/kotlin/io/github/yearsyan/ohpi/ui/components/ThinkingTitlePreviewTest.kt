package io.github.yearsyan.ohpi.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThinkingTitlePreviewTest {
    @Test
    fun shortContentIsNormalizedWithoutEllipsis() {
        assertEquals(
            "First line second line",
            thinkingTitlePreview("  First line\n\tsecond   line  "),
        )
    }

    @Test
    fun contentAtLimitIsNotEllipsized() {
        val title = "a".repeat(THINKING_TITLE_MAX_CHARACTERS)

        assertEquals(title, thinkingTitlePreview(title))
        assertEquals(title, thinkingTitlePreview("  $title  "))
    }

    @Test
    fun contentBeyondLimitHasStableBoundedPrefix() {
        val prefix = "a".repeat(THINKING_TITLE_MAX_CHARACTERS)
        val expected = "$prefix…"

        assertEquals(expected, thinkingTitlePreview(prefix + "b"))
        assertEquals(expected, thinkingTitlePreview(prefix + "a much longer suffix"))
    }

    @Test
    fun surrogatePairCountsAsOneCharacterAndIsNotSplit() {
        assertEquals("😀😀…", thinkingTitlePreview("😀😀😀", maxCharacters = 2))
    }

    @Test
    fun whitespaceOnlyContentStaysEmpty() {
        assertEquals("", thinkingTitlePreview(" \n\t "))
    }

    @Test
    fun limitMustBePositive() {
        assertFailsWith<IllegalArgumentException> {
            thinkingTitlePreview("content", maxCharacters = 0)
        }
    }
}

package io.github.yearsyan.ohpi.ui.components

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposerTranscriptScrimTest {
    @Test
    fun transcriptExtendsThroughSeventyFivePercentAndFadesOverItsLastThird() {
        val background = Color(0xFF123456)
        val stops = composerTranscriptScrimStops(background)

        assertEquals(listOf(0f, 0.5f, 0.75f, 1f), stops.map { it.first })
        assertEquals(
            listOf(Color.Transparent, Color.Transparent, background, background),
            stops.map { it.second },
        )
    }
}

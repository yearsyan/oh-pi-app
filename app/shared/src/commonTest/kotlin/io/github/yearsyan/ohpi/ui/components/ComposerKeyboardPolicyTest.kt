package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextRange
import io.github.yearsyan.ohpi.PlatformTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposerKeyboardPolicyTest {
    @Test
    fun desktopEnterSubmitsAndCtrlEnterInsertsLineBreak() {
        assertEquals(
            ComposerEnterKeyAction.Submit,
            composerEnterKeyAction(
                platformTarget = PlatformTarget.Desktop,
                eventType = KeyEventType.KeyDown,
                key = Key.Enter,
                ctrlPressed = false,
            ),
        )
        assertEquals(
            ComposerEnterKeyAction.InsertLineBreak,
            composerEnterKeyAction(
                platformTarget = PlatformTarget.Desktop,
                eventType = KeyEventType.KeyDown,
                key = Key.Enter,
                ctrlPressed = true,
            ),
        )
    }

    @Test
    fun enterPolicyDoesNotAffectMobileOrKeyUpEvents() {
        assertEquals(
            ComposerEnterKeyAction.Ignore,
            composerEnterKeyAction(
                platformTarget = PlatformTarget.Android,
                eventType = KeyEventType.KeyDown,
                key = Key.Enter,
                ctrlPressed = false,
            ),
        )
        assertEquals(
            ComposerEnterKeyAction.Ignore,
            composerEnterKeyAction(
                platformTarget = PlatformTarget.Desktop,
                eventType = KeyEventType.KeyUp,
                key = Key.Enter,
                ctrlPressed = false,
            ),
        )
    }

    @Test
    fun lineBreakReplacesSelectionAndPlacesCursorAfterIt() {
        val state =
            TextFieldState(
                initialText = "hello world",
                initialSelection = TextRange(5, 6),
            )

        state.insertComposerLineBreak()

        assertEquals("hello\nworld", state.text.toString())
        assertEquals(TextRange(6), state.selection)
    }
}

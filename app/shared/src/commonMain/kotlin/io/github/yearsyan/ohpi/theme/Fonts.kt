package io.github.yearsyan.ohpi.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ohpiapp.shared.generated.resources.Res
import ohpiapp.shared.generated.resources.jetbrains_mono_bold
import ohpiapp.shared.generated.resources.jetbrains_mono_regular
import org.jetbrains.compose.resources.Font

/**
 * Bundled JetBrains Mono for all code rendering (inline code, code fences,
 * tool payloads, paths). Guaranteed identical across Android / iOS / Desktop,
 * unlike `FontFamily.Monospace` which resolves to whatever the OS provides.
 * CJK glyphs still fall back to the system font per glyph.
 */
@Composable
fun rememberCodeFontFamily(): FontFamily {
    val regular = Font(Res.font.jetbrains_mono_regular, FontWeight.Normal)
    val bold = Font(Res.font.jetbrains_mono_bold, FontWeight.Bold)
    return remember(regular, bold) { FontFamily(regular, bold) }
}

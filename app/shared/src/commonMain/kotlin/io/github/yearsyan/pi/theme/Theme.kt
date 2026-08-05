package io.github.yearsyan.pi.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.yearsyan.pi.syntax.SyntaxColors

// ---- brand palette ----
private val Indigo = Color(0xFF4F5BD5)
private val IndigoLight = Color(0xFF8B93FF)
private val InkHigh = Color(0xFF1B1B21)
private val InkMid = Color(0xFF5B5B66)
private val Paper = Color(0xFFFFFFFF)
private val PaperDim = Color(0xFFF6F6F9)
private val NightHigh = Color(0xFFE4E2EC)
private val NightBg = Color(0xFF0E0E13)
private val NightSurface = Color(0xFF16161D)

private val LightScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E5FF),
    onPrimaryContainer = Color(0xFF1A2080),
    secondary = Color(0xFF5B5D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E1F0),
    onSecondaryContainer = Color(0xFF191A2C),
    tertiary = Color(0xFF006874),
    tertiaryContainer = Color(0xFF97F0FF),
    background = Paper,
    onBackground = InkHigh,
    surface = Paper,
    onSurface = InkHigh,
    surfaceVariant = PaperDim,
    onSurfaceVariant = InkMid,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7FA),
    surfaceContainer = Color(0xFFF2F2F6),
    surfaceContainerHigh = Color(0xFFECECF1),
    surfaceContainerHighest = Color(0xFFE5E5EB),
    outline = Color(0xFFC9C9D4),
    outlineVariant = Color(0xFFE3E3EA),
    error = Color(0xFFBA1A1A),
)

private val DarkScheme = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF141A66),
    primaryContainer = Color(0xFF333FA8),
    onPrimaryContainer = Color(0xFFE3E5FF),
    secondary = Color(0xFFC4C5DD),
    onSecondary = Color(0xFF2E2F42),
    secondaryContainer = Color(0xFF444559),
    onSecondaryContainer = Color(0xFFE0E1F0),
    tertiary = Color(0xFF4FD8EB),
    tertiaryContainer = Color(0xFF004F58),
    background = NightBg,
    onBackground = NightHigh,
    surface = NightBg,
    onSurface = NightHigh,
    surfaceVariant = NightSurface,
    onSurfaceVariant = Color(0xFF9B9BA8),
    surfaceContainerLowest = Color(0xFF0A0A0E),
    surfaceContainerLow = Color(0xFF131319),
    surfaceContainer = Color(0xFF18181F),
    surfaceContainerHigh = Color(0xFF1E1E26),
    surfaceContainerHighest = Color(0xFF25252E),
    outline = Color(0xFF3A3A46),
    outlineVariant = Color(0xFF26262E),
    error = Color(0xFFFFB4AB),
)

/** Semantic colors that do not fit Material3 slots. */
@Immutable
data class PiExtras(
    val userBubble: Color,
    val onUserBubble: Color,
    val codeBackground: Color,
    val onCode: Color,
    val success: Color,
    val warning: Color,
    val syntax: SyntaxColors,
)

private val LightExtras = PiExtras(
    userBubble = Color(0xFFE8EAFF),
    onUserBubble = Color(0xFF1B1B21),
    codeBackground = Color(0xFFEEEFF6),
    onCode = Color(0xFF2A2A35),
    success = Color(0xFF1B7F43),
    warning = Color(0xFF9A6A00),
    // GitHub-light inspired palette, readable on the light code background.
    syntax = SyntaxColors(
        keyword = Color(0xFFCF222E),
        string = Color(0xFF0A3069),
        comment = Color(0xFF6E7781),
        number = Color(0xFF0550AE),
        annotation = Color(0xFF8250DF),
    ),
)

private val DarkExtras = PiExtras(
    userBubble = Color(0xFF333FA8),
    onUserBubble = Color(0xFFEDEFFF),
    codeBackground = Color(0xFF1A1A22),
    onCode = Color(0xFFD7D7E2),
    success = Color(0xFF7BDAA3),
    warning = Color(0xFFE8C468),
    // GitHub-dark inspired palette, readable on the dark code background.
    syntax = SyntaxColors(
        keyword = Color(0xFFFF7B72),
        string = Color(0xFFA5D6FF),
        comment = Color(0xFF8B949E),
        number = Color(0xFF79C0FF),
        annotation = Color(0xFFD2A8FF),
    ),
)

val LocalPiExtras = staticCompositionLocalOf { LightExtras }

val piExtras: PiExtras
    @Composable get() = LocalPiExtras.current

private val PiShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable
fun PiTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPiExtras provides if (darkTheme) DarkExtras else LightExtras) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            shapes = PiShapes,
            content = content,
        )
    }
}

package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.yearsyan.ohpi.theme.PiTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

private const val BlurredComposerTag = "blurred-composer"
private const val BlurBackdropRootTag = "blur-backdrop-root"

@OptIn(ExperimentalTestApi::class)
class ComposerSurfaceTest {
    @Test
    fun blurredSurfaceKeepsBackdropVisible() = runComposeUiTest {
        setContent {
            PiTheme {
                val backdropState = rememberHazeState()
                Box(Modifier.size(160.dp).testTag(BlurBackdropRootTag)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .hazeSource(state = backdropState)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.Red, Color.Blue),
                                ),
                            ),
                    )
                    BlurredComposerSurface(
                        backdropState = backdropState,
                        modifier =
                            Modifier
                                .size(120.dp)
                                .align(Alignment.Center)
                                .testTag(BlurredComposerTag),
                    ) {}
                }
            }
        }

        val pixels = onNodeWithTag(BlurredComposerTag).captureToImage().toPixelMap()
        val sampleY = pixels.height / 2
        val left = pixels[pixels.width / 4, sampleY]
        val right = pixels[pixels.width * 3 / 4, sampleY]

        assertTrue(left.red > left.blue, "The left side should retain the red backdrop")
        assertTrue(right.blue > right.red, "The right side should retain the blue backdrop")

        val rootPixels = onNodeWithTag(BlurBackdropRootTag).captureToImage().toPixelMap()
        val surfaceInset = rootPixels.width / 8
        val cornerInset = rootPixels.width / 40
        val sampleX = surfaceInset + cornerInset
        val corner = rootPixels[sampleX, surfaceInset + cornerInset]
        val backdrop = rootPixels[sampleX, surfaceInset - cornerInset]

        assertTrue(
            abs(corner.red - backdrop.red) < 0.02f &&
                abs(corner.green - backdrop.green) < 0.02f &&
                abs(corner.blue - backdrop.blue) < 0.02f,
            "The blur must not draw into the rounded corner outside the surface",
        )
    }
}

package io.github.yearsyan.ohpi

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import ohpiapp.shared.generated.resources.Res
import ohpiapp.shared.generated.resources.app_icon
import org.jetbrains.compose.resources.painterResource

@Composable
fun desktopAppIconPainter(): Painter = painterResource(Res.drawable.app_icon)

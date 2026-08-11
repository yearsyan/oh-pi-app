package io.github.yearsyan.ohpi.ui.components

import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hosts the viewer in a full-screen dialog drawing edge-to-edge behind the
 * system bars. Single-tap dismissal is enabled for the touch UI.
 */
@Composable
internal actual fun ImagePreviewContainer(
    onDismiss: () -> Unit,
    title: String?,
    content: @Composable (dismissOnTap: Boolean) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = immersiveDialogProperties(),
    ) {
        ImmersiveDialogWindowEffect()
        content(true)
    }
}

/**
 * Keep the dialog window edge-to-edge. Setting this via [ImmersiveDialogWindowEffect]
 * loses to Compose re-applying [DialogProperties] on recomposition, so it must
 * be declared on the properties themselves.
 */
private fun immersiveDialogProperties(): DialogProperties =
    DialogProperties(
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false,
    )

/**
 * Lets the dialog window draw behind the status and navigation bars and keeps
 * light (white) status-bar icons over the dark viewer backdrop.
 */
@Composable
private fun ImmersiveDialogWindowEffect() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // With transparent bars the system draws a translucent scrim behind
            // them by default; disable it so the viewer backdrop shows through.
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val params = window.attributes
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = params
        }
    }
}

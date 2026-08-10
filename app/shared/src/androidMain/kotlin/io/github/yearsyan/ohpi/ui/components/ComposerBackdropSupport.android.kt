package io.github.yearsyan.ohpi.ui.components

import android.os.Build

internal actual val platformSupportsComposerBackdropBlur: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

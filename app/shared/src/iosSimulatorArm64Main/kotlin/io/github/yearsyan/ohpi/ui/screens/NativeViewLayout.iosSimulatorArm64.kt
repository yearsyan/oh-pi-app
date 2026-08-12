@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yearsyan.ohpi.ui.screens

import platform.UIKit.UIView
import platform.UIKit.setFrame

internal actual fun sizeViewToParent(child: UIView, parent: UIView) {
    child.setFrame(parent.bounds)
}

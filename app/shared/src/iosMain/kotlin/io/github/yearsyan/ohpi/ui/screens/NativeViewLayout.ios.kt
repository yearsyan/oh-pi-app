package io.github.yearsyan.ohpi.ui.screens

import platform.UIKit.UIView

/** UIKit's synthetic frame setter is available only after resolving a concrete iOS target. */
internal expect fun sizeViewToParent(child: UIView, parent: UIView)

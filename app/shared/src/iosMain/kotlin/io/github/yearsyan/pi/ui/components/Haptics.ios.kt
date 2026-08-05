package io.github.yearsyan.pi.ui.components

import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

// UIKit docs recommend reusing one generator instance for best responsiveness.
private object ImpactEngine {
    val generator = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
}

internal actual fun longPressHaptic() {
    ImpactEngine.generator.impactOccurred()
}

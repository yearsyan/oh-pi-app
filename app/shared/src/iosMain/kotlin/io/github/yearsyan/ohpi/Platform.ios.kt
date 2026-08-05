package io.github.yearsyan.ohpi

import platform.Foundation.NSBundle
import platform.UIKit.UIDevice

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
    override val target: PlatformTarget = PlatformTarget.Ios
}

actual fun getPlatform(): Platform = IOSPlatform()

actual fun appVersion(): String =
    (NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String)
        ?: "unknown"

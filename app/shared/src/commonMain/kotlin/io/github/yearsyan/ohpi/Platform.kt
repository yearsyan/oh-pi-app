package io.github.yearsyan.ohpi

enum class PlatformTarget { Android, Ios, Desktop }

interface Platform {
    val name: String
    val target: PlatformTarget

    /** True when running on Apple iOS (UIKit). */
    val isIos: Boolean get() = target == PlatformTarget.Ios
}

expect fun getPlatform(): Platform

/** Returns the installed app version (for example, "1.15"), or "unknown" when unavailable. */
expect fun appVersion(): String

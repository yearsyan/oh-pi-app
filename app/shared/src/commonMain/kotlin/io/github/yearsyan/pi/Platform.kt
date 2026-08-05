package io.github.yearsyan.pi

interface Platform {
    val name: String

    /** True when running on Apple iOS (UIKit). */
    val isIos: Boolean get() = false
}

expect fun getPlatform(): Platform

/** Returns the installed app version (for example, "1.15"), or "unknown" when unavailable. */
expect fun appVersion(): String

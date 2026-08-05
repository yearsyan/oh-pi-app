package io.github.yearsyan.pi

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

/** Returns the installed app version (for example, "1.15"), or "unknown" when unavailable. */
expect fun appVersion(): String

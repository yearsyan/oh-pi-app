package io.github.yearsyan.ohpi

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
    override val target: PlatformTarget = PlatformTarget.Desktop
}

actual fun getPlatform(): Platform = JVMPlatform()

// Keep in sync with compose.desktop.application.nativeDistributions.packageVersion in desktopApp/build.gradle.kts.
private const val DESKTOP_APP_VERSION = "2.1.0"

actual fun appVersion(): String = DESKTOP_APP_VERSION

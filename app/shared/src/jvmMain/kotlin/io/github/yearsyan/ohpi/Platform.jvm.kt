package io.github.yearsyan.ohpi

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
    override val target: PlatformTarget = PlatformTarget.Desktop
}

actual fun getPlatform(): Platform = JVMPlatform()

private const val DEFAULT_DESKTOP_APP_VERSION = "2.2.3"

actual fun appVersion(): String =
    System.getProperty("ohpi.app.version")
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_DESKTOP_APP_VERSION

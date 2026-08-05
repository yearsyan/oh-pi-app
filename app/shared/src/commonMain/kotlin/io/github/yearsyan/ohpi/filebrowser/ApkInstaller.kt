package io.github.yearsyan.ohpi.filebrowser

import androidx.compose.runtime.Composable

/** Hands downloaded APK bytes to the platform package installer. */
class ApkInstaller(
    val available: Boolean,
    private val launch: (bytes: ByteArray, fileName: String) -> String?,
) {
    /** Starts the OS install flow; returns an error message on failure. */
    fun install(bytes: ByteArray, fileName: String): String? = launch(bytes, fileName)
}

/** Android provides a real installer; other platforms report unavailable. */
@Composable
internal expect fun rememberApkInstaller(): ApkInstaller

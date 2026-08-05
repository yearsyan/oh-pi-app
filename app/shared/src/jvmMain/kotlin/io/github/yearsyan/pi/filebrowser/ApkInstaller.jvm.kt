package io.github.yearsyan.pi.filebrowser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal actual fun rememberApkInstaller(): ApkInstaller =
    remember { ApkInstaller(available = false) { _, _ -> "APK install is not supported here" } }

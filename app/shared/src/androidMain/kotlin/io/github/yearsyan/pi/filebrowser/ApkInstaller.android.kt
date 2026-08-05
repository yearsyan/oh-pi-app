package io.github.yearsyan.pi.filebrowser

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

@Composable
internal actual fun rememberApkInstaller(): ApkInstaller {
    val context = LocalContext.current
    return remember(context) {
        ApkInstaller(available = true) { bytes, fileName ->
            runCatching {
                val dir = File(context.cacheDir, "apks").apply { mkdirs() }
                val safeName = fileName
                    .ifBlank { "download.apk" }
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .takeIf { it.endsWith(".apk", ignoreCase = true) } ?: "download.apk"
                val file = File(dir, safeName)
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(
                    context,
                    context.packageName + ".fileprovider",
                    file,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME_TYPE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                null
            }.getOrElse { error -> error.message ?: "install failed" }
        }
    }
}

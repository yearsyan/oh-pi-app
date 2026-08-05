package io.github.yearsyan.ohpi

import android.os.Build
import io.github.yearsyan.ohpi.data.AndroidAppContext

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
    override val target: PlatformTarget = PlatformTarget.Android
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun appVersion(): String = runCatching {
    val context = AndroidAppContext.context
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "unknown"

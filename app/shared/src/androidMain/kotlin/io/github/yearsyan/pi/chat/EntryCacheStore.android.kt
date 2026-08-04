package io.github.yearsyan.pi.chat

import io.github.yearsyan.pi.data.AndroidAppContext
import java.io.File

internal actual fun entryCacheRootPath(): String =
    File(AndroidAppContext.context.filesDir, "entry-cache-v2").absolutePath

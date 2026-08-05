package io.github.yearsyan.ohpi.chat

import io.github.yearsyan.ohpi.data.AndroidAppContext
import java.io.File

internal actual fun entryCacheRootPath(): String =
    File(AndroidAppContext.context.filesDir, "entry-cache-v2").absolutePath

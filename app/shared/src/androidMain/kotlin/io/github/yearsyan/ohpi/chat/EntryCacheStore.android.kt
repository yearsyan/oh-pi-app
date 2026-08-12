package io.github.yearsyan.ohpi.chat

import io.github.yearsyan.ohpi.data.AndroidAppContext
import java.io.File
import okio.FileSystem

internal actual fun entryCacheRootPath(): String =
    File(AndroidAppContext.context.filesDir, "entry-cache-v2").absolutePath

internal actual fun entryCacheFileSystem(): FileSystem = FileSystem.SYSTEM

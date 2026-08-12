package io.github.yearsyan.ohpi.chat

import okio.FileSystem
import platform.Foundation.NSHomeDirectory

internal actual fun entryCacheRootPath(): String =
    NSHomeDirectory() + "/Library/Application Support/io.github.yearsyan.ohpi/entry-cache-v2"

internal actual fun entryCacheFileSystem(): FileSystem = FileSystem.SYSTEM

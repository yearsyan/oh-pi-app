package io.github.yearsyan.ohpi.chat

import java.io.File

internal actual fun entryCacheRootPath(): String =
    File(System.getProperty("user.home"), ".ohpi/entry-cache-v2").absolutePath

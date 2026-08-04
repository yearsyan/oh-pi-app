package io.github.yearsyan.pi.chat

import java.io.File

internal actual fun entryCacheRootPath(): String =
    File(System.getProperty("user.home"), ".pi-app/entry-cache-v2").absolutePath

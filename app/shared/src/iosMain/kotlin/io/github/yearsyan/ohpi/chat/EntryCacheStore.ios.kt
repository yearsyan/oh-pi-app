package io.github.yearsyan.ohpi.chat

import platform.Foundation.NSHomeDirectory

internal actual fun entryCacheRootPath(): String =
    NSHomeDirectory() + "/Library/Application Support/io.github.yearsyan.ohpi/entry-cache-v2"

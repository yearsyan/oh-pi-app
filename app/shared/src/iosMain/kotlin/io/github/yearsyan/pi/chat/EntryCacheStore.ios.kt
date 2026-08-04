package io.github.yearsyan.pi.chat

import platform.Foundation.NSHomeDirectory

internal actual fun entryCacheRootPath(): String =
    NSHomeDirectory() + "/Library/Application Support/io.github.yearsyan.pi/entry-cache-v2"

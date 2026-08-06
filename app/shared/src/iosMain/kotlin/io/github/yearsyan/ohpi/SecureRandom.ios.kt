@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yearsyan.ohpi

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

actual fun secureRandomHex(byteCount: Int): String {
    require(byteCount > 0)
    val bytes = ByteArray(byteCount)
    val status =
        bytes.usePinned { pinned ->
            SecRandomCopyBytes(kSecRandomDefault, byteCount.toULong(), pinned.addressOf(0))
        }
    check(status == errSecSuccess) { "Could not generate a secure token" }
    return bytes.joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
}

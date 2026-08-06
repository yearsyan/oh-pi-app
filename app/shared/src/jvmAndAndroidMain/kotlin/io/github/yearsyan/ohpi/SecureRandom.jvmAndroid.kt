package io.github.yearsyan.ohpi

import java.security.SecureRandom

private val secureRandom = SecureRandom()

actual fun secureRandomHex(byteCount: Int): String {
    require(byteCount > 0)
    val bytes = ByteArray(byteCount)
    secureRandom.nextBytes(bytes)
    return bytes.joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yearsyan.ohpi.data

import com.russhwolf.settings.Settings
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val KeychainService = "io.github.yearsyan.ohpi.ssh-keys"
private const val KeychainAccount = "ssh_keys"

/**
 * iOS keeps managed SSH private keys in the system Keychain instead of
 * NSUserDefaults. The whole key list is one generic-password item; it is not
 * synchronised to iCloud and does not migrate to a new device.
 */
internal class KeychainSshKeyStorage : SshKeyStorage {
    /** Reads never throw: a Keychain hiccup must not crash app start-up. */
    override fun read(): String = readKeychainItem().orEmpty()

    override fun write(value: String) {
        if (value.isEmpty()) {
            deleteKeychainItem()
        } else {
            writeKeychainItem(value)
        }
    }
}

internal actual fun createSshKeyStorage(settings: Settings): SshKeyStorage =
    KeychainSshKeyStorage()

/**
 * Builds the generic-password lookup query and hands it to [block]. The
 * dictionary retains its values; locally created strings are released after.
 */
private inline fun withBaseQuery(block: (CFDictionaryRef?) -> Unit) {
    val service = CFStringCreateWithCString(null, KeychainService, kCFStringEncodingUTF8)
    val account = CFStringCreateWithCString(null, KeychainAccount, kCFStringEncodingUTF8)
    val query =
        CFDictionaryCreateMutable(
            null,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
    try {
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, service)
        CFDictionarySetValue(query, kSecAttrAccount, account)
        block(query)
    } finally {
        CFRelease(query)
        CFRelease(service)
        CFRelease(account)
    }
}

private fun readKeychainItem(): String? =
    memScoped {
        val result = alloc<COpaquePointerVar>()
        var output: String? = null
        withBaseQuery { query ->
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) return@withBaseQuery
            val data = result.value ?: return@withBaseQuery
            val cfData: CFDataRef = data.reinterpret()
            val length = CFDataGetLength(cfData).toInt()
            val pointer = CFDataGetBytePtr(cfData)
            if (pointer != null && length > 0) {
                output = pointer.reinterpret<ByteVar>().readBytes(length).decodeToString()
            }
            CFRelease(data)
        }
        output
    }

private fun writeKeychainItem(value: String) {
    val data = stringToCFData(value)
    try {
        withBaseQuery { query ->
            CFDictionarySetValue(query, kSecValueData, data)
            CFDictionarySetValue(
                query,
                kSecAttrAccessible,
                kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            )
            when (val status = SecItemAdd(query, null)) {
                errSecSuccess -> Unit
                errSecDuplicateItem -> updateKeychainItem(data)
                else -> throw IllegalStateException("Keychain add failed (status $status)")
            }
        }
    } finally {
        CFRelease(data)
    }
}

private fun updateKeychainItem(data: CFDataRef?) {
    val attributes =
        CFDictionaryCreateMutable(
            null,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
    try {
        CFDictionarySetValue(attributes, kSecValueData, data)
        withBaseQuery { query ->
            val status = SecItemUpdate(query, attributes)
            if (status != errSecSuccess) {
                throw IllegalStateException("Keychain update failed (status $status)")
            }
        }
    } finally {
        CFRelease(attributes)
    }
}

private fun deleteKeychainItem() {
    // A missing item is not an error: nothing was stored yet.
    withBaseQuery { query -> SecItemDelete(query) }
}

private fun stringToCFData(value: String): CFDataRef? {
    val bytes = value.encodeToByteArray()
    if (bytes.isEmpty()) return null
    return bytes.usePinned { pinned ->
        CFDataCreate(null, pinned.addressOf(0).reinterpret<UByteVar>(), bytes.size.toLong())
    }
}

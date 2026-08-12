package io.github.yearsyan.ohpi.data

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

private const val MaxPrivateKeyBytes = 256 * 1024L
private const val MaxPublicKeyBytes = 64 * 1024L
private const val OpenSshPrivateKeyHeader = "-----BEGIN OPENSSH PRIVATE KEY-----"
private const val OpenSshPrivateKeyFooter = "-----END OPENSSH PRIVATE KEY-----"

private val privateKeyHeaders =
    listOf(
        OpenSshPrivateKeyHeader,
        "-----BEGIN ENCRYPTED PRIVATE KEY-----",
        "-----BEGIN PRIVATE KEY-----",
        "-----BEGIN RSA PRIVATE KEY-----",
        "-----BEGIN EC PRIVATE KEY-----",
        "-----BEGIN DSA PRIVATE KEY-----",
    )

internal actual fun discoverLocalSshKeys(): List<LocalSshKeyCandidate> {
    val userHome = System.getProperty("user.home")?.takeIf(String::isNotBlank) ?: return emptyList()
    return discoverLocalSshKeys(Path.of(userHome).resolve(".ssh"))
}

/** Visible to JVM tests so discovery can be exercised without reading the real home directory. */
internal fun discoverLocalSshKeys(directory: Path): List<LocalSshKeyCandidate> {
    if (!Files.isDirectory(directory)) return emptyList()

    val candidates = mutableListOf<LocalSshKeyCandidate>()
    Files.newDirectoryStream(directory).use { entries ->
        for (path in entries) {
            readPrivateKeyCandidate(path)?.let(candidates::add)
        }
    }
    return candidates.sortedWith(
        compareBy<LocalSshKeyCandidate>(
            { preferredKeyRank(it.fileName) },
            { it.fileName.lowercase() },
        ),
    )
}

private fun readPrivateKeyCandidate(path: Path): LocalSshKeyCandidate? =
    runCatching {
        if (!Files.isRegularFile(path)) return@runCatching null
        val size = Files.size(path)
        if (size !in 1..MaxPrivateKeyBytes) return@runCatching null

        val privateKey =
            Files.readString(path, StandardCharsets.UTF_8)
                .trimStart('\uFEFF')
                .trim()
        val header = privateKeyHeaders.firstOrNull(privateKey::startsWith)
            ?: return@runCatching null
        val fileName = path.fileName.toString()
        val publicKey = readPublicKey(path.resolveSibling("$fileName.pub"))
        LocalSshKeyCandidate(
            fileName = fileName,
            sourcePath = path.toAbsolutePath().normalize().toString(),
            privateKey = privateKey,
            publicKey = publicKey,
            keyType = detectKeyType(header, fileName, publicKey),
            encrypted = isEncryptedPrivateKey(header, privateKey),
        )
    }.getOrNull()

private fun readPublicKey(path: Path): String =
    runCatching {
        if (!Files.isRegularFile(path)) return@runCatching ""
        val size = Files.size(path)
        if (size !in 1..MaxPublicKeyBytes) return@runCatching ""
        Files.readString(path, StandardCharsets.UTF_8)
            .lineSequence()
            .map(String::trim)
            .firstOrNull(::looksLikePublicKey)
            .orEmpty()
    }.getOrDefault("")

private fun looksLikePublicKey(value: String): Boolean {
    val type = value.substringBefore(' ')
    return type == "ssh-ed25519" ||
        type == "ssh-rsa" ||
        type == "ssh-dss" ||
        type.startsWith("ecdsa-sha2-") ||
        type.startsWith("sk-ssh-ed25519@") ||
        type.startsWith("sk-ecdsa-sha2-")
}

private fun detectKeyType(header: String, fileName: String, publicKey: String): String {
    val publicType = publicKey.substringBefore(' ')
    return when {
        publicType == "ssh-ed25519" || publicType.startsWith("sk-ssh-ed25519@") -> "Ed25519"
        publicType == "ssh-rsa" -> "RSA"
        publicType == "ssh-dss" -> "DSA"
        publicType.startsWith("ecdsa-sha2-") || publicType.startsWith("sk-ecdsa-sha2-") -> "ECDSA"
        header.contains("RSA") || fileName.contains("rsa", ignoreCase = true) -> "RSA"
        header.contains("EC ") || fileName.contains("ecdsa", ignoreCase = true) -> "ECDSA"
        header.contains("DSA") || fileName.contains("dsa", ignoreCase = true) -> "DSA"
        fileName.contains("ed25519", ignoreCase = true) -> "Ed25519"
        header == "-----BEGIN ENCRYPTED PRIVATE KEY-----" ||
            header == "-----BEGIN PRIVATE KEY-----" -> "PKCS#8"
        else -> "OpenSSH"
    }
}

private fun isEncryptedPrivateKey(header: String, privateKey: String): Boolean =
    header == "-----BEGIN ENCRYPTED PRIVATE KEY-----" ||
        privateKey.lineSequence().any {
            it.startsWith("Proc-Type:", ignoreCase = true) &&
                it.contains("ENCRYPTED", ignoreCase = true)
        } ||
        (header == OpenSshPrivateKeyHeader &&
            openSshCipherName(privateKey)?.let { it != "none" } == true)

/** Reads the cipher name from the small unencrypted prefix of an openssh-key-v1 container. */
private fun openSshCipherName(privateKey: String): String? =
    runCatching {
        val encoded =
            privateKey
                .substringAfter(OpenSshPrivateKeyHeader)
                .substringBefore(OpenSshPrivateKeyFooter)
                .filterNot(Char::isWhitespace)
        val bytes = Base64.getDecoder().decode(encoded)
        val magic = "openssh-key-v1\u0000".toByteArray(StandardCharsets.US_ASCII)
        if (bytes.size < magic.size + Int.SIZE_BYTES ||
            !bytes.copyOfRange(0, magic.size).contentEquals(magic)
        ) {
            return@runCatching null
        }
        val length = ByteBuffer.wrap(bytes, magic.size, Int.SIZE_BYTES).int
        val start = magic.size + Int.SIZE_BYTES
        if (length <= 0 || start + length > bytes.size) return@runCatching null
        String(bytes, start, length, StandardCharsets.US_ASCII)
    }.getOrNull()

private fun preferredKeyRank(fileName: String): Int =
    when (fileName.lowercase()) {
        "id_ed25519" -> 0
        "id_ecdsa" -> 1
        "id_rsa" -> 2
        "id_dsa" -> 4
        else -> 3
    }

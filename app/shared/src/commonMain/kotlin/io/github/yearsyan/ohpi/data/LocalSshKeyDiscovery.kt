package io.github.yearsyan.ohpi.data

/** A private key discovered in the desktop user's ~/.ssh directory. */
internal data class LocalSshKeyCandidate(
    val fileName: String,
    val sourcePath: String,
    val privateKey: String,
    val publicKey: String = "",
    val keyType: String,
    val encrypted: Boolean,
)

/**
 * Finds recognizable OpenSSH and PEM private keys in the local user's ~/.ssh directory.
 * Unsupported platforms return an empty list.
 */
internal expect fun discoverLocalSshKeys(): List<LocalSshKeyCandidate>

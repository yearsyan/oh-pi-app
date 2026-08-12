package io.github.yearsyan.ohpi.data

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalSshKeyDiscoveryTest {
    @Test
    fun discoversPrivateKeysAndPairsPublicKeys() {
        val directory = createTempDirectory("ohpi-ssh-keys")
        try {
            Files.writeString(
                directory.resolve("id_ed25519"),
                """
                -----BEGIN OPENSSH PRIVATE KEY-----
                test-private-material
                -----END OPENSSH PRIVATE KEY-----
                """.trimIndent(),
            )
            Files.writeString(
                directory.resolve("id_ed25519.pub"),
                "ssh-ed25519 AAAATEST workstation\n",
            )
            Files.writeString(directory.resolve("config"), "Host example\n  User pi\n")
            Files.writeString(directory.resolve("known_hosts"), "example ssh-ed25519 AAAATEST\n")

            val keys = discoverLocalSshKeys(directory)

            assertEquals(1, keys.size)
            assertEquals("id_ed25519", keys.single().fileName)
            assertEquals("Ed25519", keys.single().keyType)
            assertEquals("ssh-ed25519 AAAATEST workstation", keys.single().publicKey)
            assertFalse(keys.single().encrypted)
            assertTrue(keys.single().sourcePath.endsWith("id_ed25519"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun marksEncryptedPemKeysAndSortsPreferredNamesFirst() {
        val directory = createTempDirectory("ohpi-ssh-keys")
        try {
            Files.writeString(
                directory.resolve("work.pem"),
                """
                -----BEGIN RSA PRIVATE KEY-----
                Proc-Type: 4,ENCRYPTED
                DEK-Info: AES-256-CBC,0000000000000000
                encrypted-material
                -----END RSA PRIVATE KEY-----
                """.trimIndent(),
            )
            Files.writeString(
                directory.resolve("id_rsa"),
                """
                -----BEGIN RSA PRIVATE KEY-----
                plain-material
                -----END RSA PRIVATE KEY-----
                """.trimIndent(),
            )

            val keys = discoverLocalSshKeys(directory)

            assertEquals(listOf("id_rsa", "work.pem"), keys.map { it.fileName })
            assertFalse(keys.first().encrypted)
            assertTrue(keys.last().encrypted)
            assertEquals("RSA", keys.last().keyType)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun missingSshDirectoryReturnsNoCandidates() {
        val parent = createTempDirectory("ohpi-no-ssh")
        try {
            assertTrue(discoverLocalSshKeys(parent.resolve("missing")).isEmpty())
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}

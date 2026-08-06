package io.github.yearsyan.ohpi.data

import io.github.yearsyan.ohpi.net.PiJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerProfileSerializationTest {
    @Test
    fun legacyDirectProfileKeepsBackwardCompatibleDefaults() {
        val profile =
            PiJson.decodeFromString<ServerProfile>(
                """{"id":"old","name":"Workstation","url":"ws://host:8080","token":"token"}""",
            )

        assertEquals(ServerConnectionMode.Direct, profile.connectionMode)
        assertEquals(SshServerProfile(), profile.ssh)
        assertEquals(emptyList(), profile.portForwards)
    }

    @Test
    fun sshProfilePersistsKeyReferenceButNotKeyMaterial() {
        val original =
            ServerProfile(
                id = "ssh",
                name = "Remote",
                url = "http://127.0.0.1:8080",
                token = "token",
                connectionMode = ServerConnectionMode.Ssh,
                ssh =
                    SshServerProfile(
                        host = "ssh.example",
                        username = "pi",
                        authentication = SshAuthentication.PrivateKey,
                        privateKeyId = "key-abc",
                        hostKeySha256 = "SHA256:trusted",
                        privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nkey\n-----END OPENSSH PRIVATE KEY-----",
                        privateKeyPassphrase = "passphrase",
                    ),
            )

        val encoded = PiJson.encodeToString(original)
        val decoded = PiJson.decodeFromString<ServerProfile>(encoded)

        // The managed-key id and trust survive; injected key material does not.
        assertEquals(original.copy(ssh = original.ssh.copy(privateKey = "", privateKeyPassphrase = "")), decoded)
        assertEquals("key-abc", decoded.ssh.privateKeyId)
        assertEquals("SHA256:trusted", decoded.ssh.hostKeySha256)
        assertEquals("", decoded.ssh.privateKey)
        assertEquals("", decoded.ssh.privateKeyPassphrase)
        assertTrue(!encoded.contains("OPENSSH PRIVATE KEY"))
        assertTrue(!encoded.contains("passphrase"))
    }

    @Test
    fun portForwardsRoundTrip() {
        val original =
            ServerProfile(
                id = "ssh",
                name = "Remote",
                url = "http://127.0.0.1:8080",
                token = "token",
                connectionMode = ServerConnectionMode.Ssh,
                portForwards =
                    listOf(
                        PortForward(id = "pf-1", remotePort = 3000),
                        PortForward(
                            id = "pf-2",
                            remoteHost = "10.0.0.2",
                            remotePort = 5432,
                            enabled = false,
                        ),
                    ),
            )

        val decoded = PiJson.decodeFromString<ServerProfile>(PiJson.encodeToString(original))

        assertEquals(original.portForwards, decoded.portForwards)
    }

    @Test
    fun managedSshModeRoundTrips() {
        val original =
            ServerProfile(
                id = "managed",
                name = "Managed",
                url = "ws://127.0.0.1:18080",
                token = "managed-token",
                connectionMode = ServerConnectionMode.ManagedSsh,
                ssh = SshServerProfile(host = "host", username = "user", password = "password"),
            )

        val decoded = PiJson.decodeFromString<ServerProfile>(PiJson.encodeToString(original))

        assertEquals(original, decoded)
    }
}

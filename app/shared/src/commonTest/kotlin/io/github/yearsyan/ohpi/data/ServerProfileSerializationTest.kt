package io.github.yearsyan.ohpi.data

import io.github.yearsyan.ohpi.net.PiJson
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerProfileSerializationTest {
    @Test
    fun legacyDirectProfileKeepsBackwardCompatibleDefaults() {
        val profile =
            PiJson.decodeFromString<ServerProfile>(
                """{"id":"old","name":"Workstation","url":"ws://host:8080","token":"token"}""",
            )

        assertEquals(ServerConnectionMode.Direct, profile.connectionMode)
        assertEquals(SshServerProfile(), profile.ssh)
    }

    @Test
    fun sshProfileRoundTripsTrustedFingerprintAndInMemoryKey() {
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
                        privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nkey\n-----END OPENSSH PRIVATE KEY-----",
                        hostKeySha256 = "SHA256:trusted",
                    ),
            )

        val decoded = PiJson.decodeFromString<ServerProfile>(PiJson.encodeToString(original))

        assertEquals(original, decoded)
    }
}

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
        assertEquals(emptyList(), profile.portForwards)
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
}

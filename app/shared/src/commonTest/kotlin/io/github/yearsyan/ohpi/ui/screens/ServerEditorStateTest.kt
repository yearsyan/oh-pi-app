package io.github.yearsyan.ohpi.ui.screens

import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.data.ServerConnectionMode
import io.github.yearsyan.ohpi.i18n.EnStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerEditorStateTest {
    @Test
    fun newProfilesUseGatewayDefaultPort() {
        assertEquals("18080", ServerEditorState(null).gatewayPort)
    }

    @Test
    fun loadsLegacyUrlIntoSeparateFields() {
        val editor =
            ServerEditorState(
                ServerProfile(
                    id = "server",
                    name = "Gateway",
                    url = "https://gateway.example.com:9443",
                    token = "token",
                ),
            )

        assertEquals("gateway.example.com", editor.gatewayHost)
        assertEquals("9443", editor.gatewayPort)
        assertEquals(true, editor.gatewayTls)
    }

    @Test
    fun buildsInternalUrlFromHostPortAndTls() {
        val editor = ServerEditorState(null)
        editor.gatewayHost = "192.168.9.138"
        editor.gatewayPort = "18080"
        editor.gatewayTls = false

        val profile = assertNotNull(editor.build(EnStrings))

        assertEquals("ws://192.168.9.138:18080", profile.url)
    }

    @Test
    fun rejectsInvalidGatewayPort() {
        val editor = ServerEditorState(null)
        editor.gatewayHost = "192.168.9.138"
        editor.gatewayPort = "70000"

        assertNull(editor.build(EnStrings))
        assertEquals(EnStrings.serverPortInvalid, editor.error)
    }

    @Test
    fun managedSshOwnsGatewayAddressAndToken() {
        val editor = ServerEditorState(null)
        editor.connectionMode = ServerConnectionMode.ManagedSsh
        editor.sshHost = "host.example"
        editor.sshUsername = "user"
        editor.sshPassword = "password"

        val profile = assertNotNull(editor.build(EnStrings))

        assertEquals("ws://127.0.0.1:18080", profile.url)
        assertEquals(ServerConnectionMode.ManagedSsh, profile.connectionMode)
        assertEquals(64, profile.token.length)
        assertTrue(profile.token.all { it in "0123456789abcdef" })
    }
}

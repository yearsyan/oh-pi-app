package io.github.yearsyan.ohpi.ui.screens

import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.data.ServerConnectionMode
import io.github.yearsyan.ohpi.data.SshAuthentication
import io.github.yearsyan.ohpi.data.SshPrivateKey
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

        val profile = assertNotNull(editor.build(EnStrings)).profile

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

        val profile = assertNotNull(editor.build(EnStrings)).profile

        assertEquals("ws://127.0.0.1:18080", profile.url)
        assertEquals(ServerConnectionMode.ManagedSsh, profile.connectionMode)
        assertEquals(64, profile.token.length)
        assertTrue(profile.token.all { it in "0123456789abcdef" })
    }

    @Test
    fun privateKeyAuthRequiresASelectedOrNewKey() {
        val editor = ServerEditorState(null)
        editor.gatewayHost = "192.168.9.138"
        editor.sshHost = "host.example"
        editor.sshUsername = "user"
        editor.connectionMode = ServerConnectionMode.Ssh
        editor.sshAuthentication = SshAuthentication.PrivateKey

        assertNull(editor.build(EnStrings, keys = listOf(managedKey("key-1"))))
        assertEquals(EnStrings.sshKeyRequired, editor.error)
    }

    @Test
    fun privateKeyAuthReferencesSelectedManagedKey() {
        val editor = ServerEditorState(null)
        editor.gatewayHost = "192.168.9.138"
        editor.sshHost = "host.example"
        editor.sshUsername = "user"
        editor.connectionMode = ServerConnectionMode.Ssh
        editor.sshAuthentication = SshAuthentication.PrivateKey
        editor.keySelection.selectedKeyId = "key-1"

        val result = assertNotNull(editor.build(EnStrings, keys = listOf(managedKey("key-1"))))

        assertNull(result.newKey)
        assertEquals("key-1", result.profile.ssh.privateKeyId)
        assertEquals("", result.profile.ssh.privateKey)
    }

    @Test
    fun privateKeyAuthImportsNewKeyAndReferencesIt() {
        val editor = ServerEditorState(null)
        editor.gatewayHost = "192.168.9.138"
        editor.sshHost = "host.example"
        editor.sshUsername = "user"
        editor.connectionMode = ServerConnectionMode.Ssh
        editor.sshAuthentication = SshAuthentication.PrivateKey
        editor.keySelection.newKeyName = "  Laptop key  "
        editor.keySelection.newKeyContents = "  -----BEGIN KEY-----\nabc\n-----END KEY-----\n"
        editor.keySelection.newKeyPassphrase = "secret"
        editor.keySelection.newKeyPublicKey = "  ssh-ed25519 AAAATEST laptop  "

        // No managed keys exist, so importing a new one is the implied choice.
        val result = assertNotNull(editor.build(EnStrings, keys = emptyList()))
        val newKey = assertNotNull(result.newKey)

        assertEquals("Laptop key", newKey.name)
        assertEquals("-----BEGIN KEY-----\nabc\n-----END KEY-----", newKey.privateKey)
        assertEquals("secret", newKey.passphrase)
        assertEquals("ssh-ed25519 AAAATEST laptop", newKey.publicKey)
        assertEquals(newKey.id, result.profile.ssh.privateKeyId)
    }

    @Test
    fun keySelectionValidationCoversBlankNewKeyContents() {
        val selection = SshKeySelectionState()
        selection.creatingNew = true
        selection.newKeyContents = ""

        assertEquals(
            EnStrings.sshPrivateKeyRequired,
            selection.validate(EnStrings, keys = listOf(managedKey("key-1"))),
        )
        selection.newKeyContents = "key material"
        assertNull(selection.validate(EnStrings, keys = listOf(managedKey("key-1"))))
    }

    @Test
    fun discoveredEncryptedKeyRequiresItsPassphrase() {
        val selection = SshKeySelectionState()
        selection.creatingNew = true
        selection.newKeyContents = "encrypted key material"
        selection.newKeyEncrypted = true

        assertEquals(
            EnStrings.sshLocalKeyPassphraseRequired,
            selection.validate(EnStrings, keys = emptyList()),
        )

        selection.newKeyPassphrase = "secret"
        assertNull(selection.validate(EnStrings, keys = emptyList()))
    }

    private fun managedKey(id: String) =
        SshPrivateKey(id = id, name = "Key $id", privateKey = "material")
}

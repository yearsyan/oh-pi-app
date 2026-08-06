package io.github.yearsyan.ohpi.ui.screens

import io.github.yearsyan.ohpi.data.ServerConnectionMode
import io.github.yearsyan.ohpi.data.SshAuthentication
import io.github.yearsyan.ohpi.data.SshPrivateKey
import io.github.yearsyan.ohpi.i18n.EnStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingFormStateTest {
    @Test
    fun buildsManagedProfileWithGeneratedToken() {
        val form = NewMachineFormState()
        form.sshHost = "host.example"
        form.sshUsername = "user"
        form.password = "password"

        val result = assertNotNull(form.build(EnStrings, keys = emptyList()))

        assertNull(result.newKey)
        assertEquals(ServerConnectionMode.ManagedSsh, result.profile.connectionMode)
        assertEquals("ws://127.0.0.1:18080", result.profile.url)
        assertEquals("host.example", result.profile.name)
        assertEquals(64, result.profile.token.length)
        assertEquals("password", result.profile.ssh.password)
        assertEquals("", result.profile.ssh.privateKeyId)
    }

    @Test
    fun rejectsBlankSshHost() {
        val form = NewMachineFormState()
        form.sshUsername = "user"
        form.password = "password"

        assertNull(form.build(EnStrings, keys = emptyList()))
        assertEquals(EnStrings.sshHostRequired, form.error)
    }

    @Test
    fun rejectsInvalidSshPort() {
        val form = NewMachineFormState()
        form.sshHost = "host.example"
        form.sshPort = "70000"
        form.sshUsername = "user"
        form.password = "password"

        assertNull(form.build(EnStrings, keys = emptyList()))
        assertEquals(EnStrings.sshPortInvalid, form.error)
    }

    @Test
    fun selectedManagedKeyMaterialIsInjectedForInstallRun() {
        val key = SshPrivateKey(id = "key-1", name = "Laptop", privateKey = "material", passphrase = "pp")
        val form = NewMachineFormState()
        form.sshHost = "host.example"
        form.sshUsername = "user"
        form.authentication = SshAuthentication.PrivateKey
        form.keySelection.selectedKeyId = "key-1"

        val result = assertNotNull(form.build(EnStrings, keys = listOf(key)))

        assertNull(result.newKey)
        assertEquals("key-1", result.profile.ssh.privateKeyId)
        assertEquals("material", result.profile.ssh.privateKey)
        assertEquals("pp", result.profile.ssh.privateKeyPassphrase)
    }

    @Test
    fun newKeyIsImportedAndReferencedByProfile() {
        val form = NewMachineFormState()
        form.sshHost = "host.example"
        form.sshUsername = "user"
        form.authentication = SshAuthentication.PrivateKey
        form.keySelection.newKeyContents = "  key material  "
        form.keySelection.newKeyName = " "

        val result = assertNotNull(form.build(EnStrings, keys = emptyList()))
        val newKey = assertNotNull(result.newKey)

        assertEquals("key material", newKey.privateKey)
        assertEquals(EnStrings.sshKeyDefaultName, newKey.name)
        assertEquals(newKey.id, result.profile.ssh.privateKeyId)
        assertEquals(newKey.privateKey, result.profile.ssh.privateKey)
        assertTrue(newKey.id.startsWith("key-"))
    }
}

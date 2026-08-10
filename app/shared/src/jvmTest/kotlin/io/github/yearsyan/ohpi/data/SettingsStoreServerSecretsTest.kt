package io.github.yearsyan.ohpi.data

import com.russhwolf.settings.PreferencesSettings
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStoreServerSecretsTest {
    private val node =
        Preferences.userRoot().node("io.github.yearsyan.ohpi.secrets-test-${System.nanoTime()}")
    private val secureStorage = MemoryServerSecretStorage()
    private val store =
        SettingsStore(PreferencesSettings(node)).also {
            it.useServerSecretStorageForTest(secureStorage)
        }

    @AfterTest
    fun cleanup() {
        node.clear()
        node.removeNode()
    }

    @Test
    fun secureStorageRoundTripKeepsSecretsOutOfSettings() {
        val profile = secretProfile()

        store.saveServers(listOf(profile))

        val settingsBlob = node.get("servers", "")
        assertFalse(settingsBlob.contains("gateway-token"))
        assertFalse(settingsBlob.contains("ssh-password"))
        assertTrue(secureStorage.value.contains("gateway-token"))
        assertTrue(secureStorage.value.contains("ssh-password"))
        assertEquals(listOf(profile), store.loadServers())
    }

    @Test
    fun deletingLastServerClearsSecureStorage() {
        store.saveServers(listOf(secretProfile()))

        store.saveServers(emptyList())

        assertEquals("", secureStorage.value)
        assertEquals(emptyList(), store.loadServers())
    }

    @Test
    fun deletingOneServerRemovesOnlyItsSecrets() {
        val first = secretProfile()
        val second =
            secretProfile().copy(
                id = "server-2",
                token = "second-token",
                ssh = secretProfile().ssh.copy(password = "second-password"),
            )
        store.saveServers(listOf(first, second))

        store.saveServers(listOf(second))

        assertFalse(secureStorage.value.contains("gateway-token"))
        assertFalse(secureStorage.value.contains("ssh-password"))
        assertTrue(secureStorage.value.contains("second-token"))
        assertTrue(secureStorage.value.contains("second-password"))
        assertEquals(listOf(second), store.loadServers())
    }

    @Test
    fun metadataOnlySaveDoesNotRewriteUnchangedSecrets() {
        val profile = secretProfile()
        store.saveServers(listOf(profile))
        val writesAfterInitialSave = secureStorage.writeCount

        store.saveServers(listOf(profile.copy(name = "Renamed")))

        assertEquals(writesAfterInitialSave, secureStorage.writeCount)
        assertEquals("Renamed", store.loadServers().single().name)
    }

    @Test
    fun legacyPlaintextSecretsAreDiscardedRatherThanImported() {
        node.put(
            "servers",
            """[{"id":"legacy","name":"Legacy","url":"ws://host","token":"legacy-token","connectionMode":"Ssh","ssh":{"host":"host","username":"user","password":"legacy-password"}}]""",
        )

        val loaded = store.loadServers().single()

        assertEquals("", loaded.token)
        assertEquals("", loaded.ssh.password)
        assertEquals("", secureStorage.value)
        assertFalse(node.get("servers", "").contains("legacy-token"))
        assertFalse(node.get("servers", "").contains("legacy-password"))
    }

    private fun secretProfile(): ServerProfile =
        ServerProfile(
            id = "server-1",
            name = "Server",
            url = "ws://host",
            token = "gateway-token",
            connectionMode = ServerConnectionMode.Ssh,
            ssh =
                SshServerProfile(
                    host = "host",
                    username = "user",
                    password = "ssh-password",
                ),
        )
}

private class MemoryServerSecretStorage : ServerSecretStorage {
    override val protectsSecrets: Boolean = true
    var value: String = ""
    var writeCount: Int = 0

    override fun read(): String = value

    override fun write(value: String) {
        writeCount++
        this.value = value
    }
}

package io.github.yearsyan.ohpi.data

import com.russhwolf.settings.PreferencesSettings
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsStoreSshKeysTest {
    private val node =
        Preferences.userRoot().node("io.github.yearsyan.ohpi.test-${System.nanoTime()}")
    private val store = SettingsStore(PreferencesSettings(node))

    @AfterTest
    fun cleanup() {
        node.clear()
        node.removeNode()
    }

    @Test
    fun sshKeysRoundTripThroughStorageBackend() {
        assertTrue(store.loadSshKeys().isEmpty())

        val keys =
            listOf(
                SshPrivateKey(id = "key-1", name = "Laptop", privateKey = "material-1"),
                SshPrivateKey(
                    id = "key-2",
                    name = "CI key",
                    privateKey = "material-2",
                    passphrase = "secret",
                ),
            )
        store.saveSshKeys(keys)

        assertEquals(keys, store.loadSshKeys())
    }

    @Test
    fun savingEmptyKeyListClearsStorage() {
        store.saveSshKeys(listOf(SshPrivateKey(id = "key-1", name = "k", privateKey = "m")))
        store.saveSshKeys(emptyList())

        assertTrue(store.loadSshKeys().isEmpty())
    }

    @Test
    fun corruptKeyBlobDecodesAsEmpty() {
        store.saveSshKeys(listOf(SshPrivateKey(id = "key-1", name = "k", privateKey = "m")))
        node.put("ssh_keys", "not json")

        assertTrue(store.loadSshKeys().isEmpty())
    }
}

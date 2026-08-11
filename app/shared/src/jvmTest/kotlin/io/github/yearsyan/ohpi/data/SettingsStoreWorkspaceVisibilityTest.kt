package io.github.yearsyan.ohpi.data

import com.russhwolf.settings.PreferencesSettings
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsStoreWorkspaceVisibilityTest {
    private val node =
        Preferences.userRoot().node("io.github.yearsyan.ohpi.workspaces-test-${System.nanoTime()}")
    private val store = SettingsStore(PreferencesSettings(node))

    @AfterTest
    fun cleanup() {
        node.clear()
        node.removeNode()
    }

    @Test
    fun workspaceVisibilityIsScopedToEachServer() {
        store.saveArchivedWorkspaceIds("server-a", setOf("workspace-b", "workspace-a"))
        store.saveDeletedWorkspaceIds("server-a", setOf("workspace-c"))

        assertEquals(setOf("workspace-a", "workspace-b"), store.archivedWorkspaceIds("server-a"))
        assertEquals(setOf("workspace-c"), store.deletedWorkspaceIds("server-a"))
        assertTrue(store.archivedWorkspaceIds("server-b").isEmpty())
        assertTrue(store.deletedWorkspaceIds("server-b").isEmpty())
    }

    @Test
    fun clearingServerVisibilityAlsoClearsItsLastWorkspace() {
        store.saveArchivedWorkspaceIds("server", setOf("workspace-a"))
        store.saveDeletedWorkspaceIds("server", setOf("workspace-b"))
        store.saveLastWorkspaceId("server", "workspace-a")

        store.clearWorkspaceVisibility("server")

        assertTrue(store.archivedWorkspaceIds("server").isEmpty())
        assertTrue(store.deletedWorkspaceIds("server").isEmpty())
        assertEquals("", store.lastWorkspaceId("server"))
    }
}

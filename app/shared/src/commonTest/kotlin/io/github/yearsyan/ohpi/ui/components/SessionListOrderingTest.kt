package io.github.yearsyan.ohpi.ui.components

import io.github.yearsyan.ohpi.data.WorkspaceSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionListOrderingTest {
    @Test
    fun workspaceDisplayNameUsesMetadataBeforeDirectory() {
        assertEquals(
            "Mobile client",
            WorkspaceSummary(id = "one", directory = "/srv/app", name = "Mobile client").displayName,
        )
        assertEquals("app", WorkspaceSummary(id = "two", directory = "/srv/app").displayName)
        assertEquals("app", WorkspaceSummary(id = "three", directory = "C:\\src\\app\\").displayName)
    }

    @Test
    fun technologyBadgesCoverPrimaryDetectedStacks() {
        assertEquals("Go", technologyVisual("go").label)
        assertEquals("Rs", technologyVisual("rust").label)
        assertEquals("TS", technologyVisual("typescript").label)
        assertEquals("V", technologyVisual("vite").label)
        assertEquals("<>", technologyVisual("unknown").label)
    }
}

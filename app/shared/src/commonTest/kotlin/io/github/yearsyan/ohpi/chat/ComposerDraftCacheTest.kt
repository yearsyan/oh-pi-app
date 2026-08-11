package io.github.yearsyan.ohpi.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ComposerDraftCacheTest {
    private val image = PromptImage(
        data = "aGVsbG8=",
        mimeType = "image/png",
        name = "screen.png",
    )

    @Test
    fun workspaceAndSessionDraftsUseDistinctKeys() {
        val cache = ComposerDraftCache()
        val workspace = ComposerDraftKey.Workspace("server", "same-id")
        val session = ComposerDraftKey.Session("server", "same-id")

        cache.write(workspace, ComposerDraft("new chat"))
        cache.write(session, ComposerDraft("history"))

        assertEquals("new chat", cache.read(workspace).text)
        assertEquals("history", cache.read(session).text)
    }

    @Test
    fun draftsAreIsolatedByWorkspaceSessionAndServer() {
        val cache = ComposerDraftCache()
        val firstWorkspace = ComposerDraftKey.Workspace("server-a", "workspace-a")
        val secondWorkspace = ComposerDraftKey.Workspace("server-a", "workspace-b")
        val otherServer = ComposerDraftKey.Workspace("server-b", "workspace-a")
        val firstSession = ComposerDraftKey.Session("server-a", "session-a")
        val secondSession = ComposerDraftKey.Session("server-a", "session-b")

        cache.write(firstWorkspace, ComposerDraft("workspace a"))
        cache.write(secondWorkspace, ComposerDraft("workspace b"))
        cache.write(otherServer, ComposerDraft("other server"))
        cache.write(firstSession, ComposerDraft("session a"))
        cache.write(secondSession, ComposerDraft("session b"))

        assertEquals("workspace a", cache.read(firstWorkspace).text)
        assertEquals("workspace b", cache.read(secondWorkspace).text)
        assertEquals("other server", cache.read(otherServer).text)
        assertEquals("session a", cache.read(firstSession).text)
        assertEquals("session b", cache.read(secondSession).text)
    }

    @Test
    fun movingCreatedDraftUsesLiveContentsAndRemovesWorkspaceEntry() {
        val cache = ComposerDraftCache()
        val workspace = ComposerDraftKey.Workspace("server", "workspace")
        val session = ComposerDraftKey.Session("server", "session")
        cache.write(workspace, ComposerDraft("cached"))
        cache.write(session, ComposerDraft("stale session"))

        cache.move(workspace, session, ComposerDraft("live", listOf(image)))

        assertEquals(ComposerDraft(), cache.read(workspace))
        assertEquals(ComposerDraft("live", listOf(image)), cache.read(session))
        assertEquals(1, cache.size)
    }

    @Test
    fun cacheSnapshotsImageListsAndEvictsEmptyDrafts() {
        val cache = ComposerDraftCache()
        val key = ComposerDraftKey.Session("server", "session")
        val images = mutableListOf(image)

        cache.write(key, ComposerDraft("inspect", images))
        images.clear()

        assertEquals(listOf(image), cache.read(key).images)
        cache.write(key, ComposerDraft())
        assertEquals(ComposerDraft(), cache.read(key))
        assertEquals(0, cache.size)
    }
}

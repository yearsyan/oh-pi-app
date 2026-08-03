package io.github.yearsyan.pi.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeSshLibraryLoaderTest {
    @Test
    fun resolvesWindowsX64ResourceClassifier() {
        assertEquals("windows-x86_64", nativeSshClassifier("Windows 11", "amd64"))
        assertEquals("windows-x86_64", nativeSshClassifier("Windows 10", "x86_64"))
    }

    @Test
    fun rejectsUnpackagedWindowsArchitecture() {
        assertFailsWith<IllegalStateException> {
            nativeSshClassifier("Windows 11", "aarch64")
        }
    }
}

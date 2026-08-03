package io.github.yearsyan.pi

import kotlin.test.Test
import kotlin.test.assertContains

class NativeSshLoadingTest {
    @Test
    fun loadsPackagedWindowsJniLibrary() {
        val platformClass = Class.forName("io.github.yearsyan.pi.ssh.PlatformSsh")
        val platform = platformClass.getField("INSTANCE").get(null)
        val version =
            platformClass
                .getDeclaredMethod("libraryVersion")
                .invoke(platform) as String

        assertContains(version, "0.12.1")
    }
}

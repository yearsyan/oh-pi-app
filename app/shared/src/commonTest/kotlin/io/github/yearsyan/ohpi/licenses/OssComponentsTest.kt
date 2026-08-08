package io.github.yearsyan.ohpi.licenses

import io.github.yearsyan.ohpi.PlatformTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OssComponentsTest {
    @Test
    fun dependencyFamiliesKeepTheirOwnVersions() {
        val versions = OSS_COMPONENTS.associate { it.name to it.version }

        assertEquals("2.4.10", versions["Kotlin"])
        assertEquals("1.11.0", versions["kotlinx.coroutines"])
        assertEquals("1.9.0", versions["kotlinx.serialization"])
        assertEquals("0.7.1", versions["kotlinx-datetime"])
        assertEquals("1.13.0", versions["AndroidX Activity Compose"])
        assertEquals("2.11.0-beta01", versions["AndroidX Lifecycle for Compose Multiplatform"])
        assertEquals("2.9.2", versions["AndroidX Navigation for Compose Multiplatform"])
        assertEquals("0.41.0", versions["Kotlin Multiplatform Markdown Renderer"])
        assertEquals("0.7.3", versions["JetBrains Markdown"])
        assertEquals("1.1.0", versions["Highlights"])
    }

    @Test
    fun platformSpecificComponentsOnlyAppearOnTheirTarget() {
        val androidNames = ossComponentsFor(PlatformTarget.Android).map { it.name }
        val iosNames = ossComponentsFor(PlatformTarget.Ios).map { it.name }
        val desktopNames = ossComponentsFor(PlatformTarget.Desktop).map { it.name }

        assertTrue("AndroidX Activity Compose" in androidNames)
        assertTrue("AndroidX Core" in androidNames)
        assertFalse("Java Native Access (JNA)" in androidNames)
        assertFalse("AndroidX Activity Compose" in iosNames)
        assertFalse("Java Native Access (JNA)" in iosNames)
        assertTrue("Java Native Access (JNA)" in desktopNames)
        assertFalse("AndroidX Activity Compose" in desktopNames)
    }
}

package io.github.yearsyan.ohpi.licenses

import io.github.yearsyan.ohpi.PlatformTarget

/**
 * Open-source components bundled into each app target, for display in the
 * Settings → About → Open-source licenses screen.
 *
 * Versions are maintained per component from the production Gradle graphs and
 * the native libraries linked by `native/pi_ssh`. Keep both versions and
 * target sets in sync when upgrading.
 */
enum class OssCategory { App, Framework, Native }

enum class OssLicense(val spdx: String) {
    Apache2("Apache-2.0"),
    Mit("MIT"),
    Lgpl21OrLater("LGPL-2.1-or-later"),
    Ofl11("OFL-1.1");

    val fullText: String
        get() = when (this) {
            Apache2 -> APACHE_2_0_TEXT
            Mit -> MIT_TEXT
            Lgpl21OrLater -> LGPL_2_1_TEXT
            Ofl11 -> OFL_1_1_TEXT
        }
}

data class OssComponent(
    val name: String,
    val version: String,
    val copyright: String,
    val license: OssLicense,
    val category: OssCategory,
    val platforms: Set<PlatformTarget> = PlatformTarget.entries.toSet(),
)

internal val OSS_COMPONENTS: List<OssComponent> = listOf(
    // ---- Frameworks & libraries (Apache 2.0) ----
    OssComponent(
        name = "Kotlin",
        version = "2.4.10",
        copyright = "JetBrains s.r.o.",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
    ),
    OssComponent(
        name = "kotlinx.coroutines",
        version = "1.11.0",
        copyright = "JetBrains s.r.o.",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
    ),
    OssComponent(
        name = "kotlinx.serialization",
        version = "1.9.0",
        copyright = "JetBrains s.r.o.",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
    ),
    OssComponent(
        name = "kotlinx-datetime",
        version = "0.7.1",
        copyright = "JetBrains s.r.o.",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
    ),
    OssComponent(
        name = "Compose Multiplatform",
        version = "1.11.1",
        copyright = "JetBrains s.r.o.",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
    ),
    OssComponent(
        name = "Material 3 for Compose Multiplatform",
        version = "1.11.0-alpha07",
        copyright = "JetBrains s.r.o. / Google LLC",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
    ),
    OssComponent(
        name = "Material Icons",
        version = "1.7.3",
        copyright = "Google LLC",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
    ),
    OssComponent(
        name = "AndroidX Activity Compose",
        version = "1.13.0",
        copyright = "The Android Open Source Project",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
        platforms = setOf(PlatformTarget.Android),
    ),
    OssComponent(
        name = "AndroidX Core",
        version = "1.18.0",
        copyright = "The Android Open Source Project",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
        platforms = setOf(PlatformTarget.Android),
    ),
    OssComponent(
        name = "AndroidX Lifecycle for Compose Multiplatform",
        version = "2.11.0-beta01",
        copyright = "The Android Open Source Project",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
    ),
    OssComponent(
        name = "AndroidX Navigation for Compose Multiplatform",
        version = "2.9.2",
        copyright = "The Android Open Source Project",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
    ),
    OssComponent(
        name = "Ktor (HTTP / WebSocket client)",
        version = "3.3.3",
        copyright = "JetBrains s.r.o.",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
    ),
    OssComponent(
        name = "Okio",
        version = "3.4.0",
        copyright = "Square, Inc.",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
    ),
    OssComponent(
        name = "multiplatform-settings",
        version = "1.3.0",
        copyright = "Russell Wolf",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
    ),
    OssComponent(
        name = "Kotlin Multiplatform Markdown Renderer",
        version = "0.41.0",
        copyright = "Mike Penz & contributors",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
    ),
    OssComponent(
        name = "JetBrains Markdown",
        version = "0.7.3",
        copyright = "JetBrains s.r.o.",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
    ),
    OssComponent(
        name = "Highlights",
        version = "1.1.0",
        copyright = "Tomasz Kądziołka & contributors",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
    ),
    OssComponent(
        name = "Java Native Access (JNA)",
        version = "5.18.1",
        copyright = "Timothy Wall & JNA contributors",
        license = OssLicense.Apache2,
        category = OssCategory.Framework,
        platforms = setOf(PlatformTarget.Desktop),
    ),

    // ---- Bundled assets ----
    OssComponent(
        name = "JetBrains Mono (code font)",
        version = "2.304",
        copyright = "JetBrains s.r.o. / The JetBrains Mono Project Authors",
        license = OssLicense.Ofl11,
        category = OssCategory.App,
    ),

    // ---- Native libraries (statically linked by pi_ssh) ----
    OssComponent(
        name = "libssh",
        version = "0.12.1",
        copyright = "Aris Adamantiadis, Andreas Schneider & libssh contributors",
        license = OssLicense.Lgpl21OrLater,
        category = OssCategory.Native,
    ),
    OssComponent(
        name = "Mbed TLS",
        version = "3.6.6",
        copyright = "ARM Limited & Mbed TLS contributors",
        license = OssLicense.Apache2,
        category = OssCategory.Native,
    ),
)

internal fun ossComponentsFor(platform: PlatformTarget): List<OssComponent> =
    OSS_COMPONENTS.filter { platform in it.platforms }

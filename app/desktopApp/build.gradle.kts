import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.Sync

val hostOs = System.getProperty("os.name").lowercase()
val hostArch = System.getProperty("os.arch").lowercase()
val piSshClassifier =
    when {
        hostOs.contains("mac") && hostArch in setOf("aarch64", "arm64") -> "macos-aarch64"
        hostOs.contains("mac") && hostArch in setOf("x86_64", "amd64") -> "macos-x86_64"
        hostOs.contains("linux") && hostArch in setOf("x86_64", "amd64") -> "linux-x86_64"
        hostOs.contains("windows") && hostArch in setOf("x86_64", "amd64") -> "windows-x86_64"
        else -> error("pi_ssh desktop build is not configured for $hostOs/$hostArch")
    }
val piSshLibraryName =
    when {
        hostOs.contains("mac") -> "libpi_ssh.dylib"
        hostOs.contains("linux") -> "libpi_ssh.so"
        else -> "pi_ssh.dll"
    }
val piSshSourceDir = rootProject.projectDir.parentFile.resolve("native/pi_ssh")
val piSshBuildDir = layout.buildDirectory.dir("piSsh/cmake/$piSshClassifier")
val piSshOutputDir = layout.buildDirectory.dir("piSsh/output/$piSshClassifier")
val piSshResourcesDir = layout.buildDirectory.dir("generated/piSshResources")
val piSshBuildPath = piSshBuildDir.get().asFile.absolutePath
val piSshOutputPath = piSshOutputDir.get().asFile.absolutePath
val desktopAppVersion = providers.gradleProperty("versionName").getOrElse("2.3.2")

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)

    testImplementation(libs.kotlin.testJunit)
}

val configurePiSshDesktop by tasks.registering(Exec::class) {
    inputs.dir(piSshSourceDir)
    outputs.file(piSshBuildDir.map { it.file("CMakeCache.txt") })
    commandLine(
        "cmake",
        "-S",
        piSshSourceDir.absolutePath,
        "-B",
        piSshBuildPath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DJAVA_HOME=${System.getProperty("java.home")}",
        "-DPI_SSH_ENABLE_JNI=ON",
        "-DPI_SSH_BUILD_TESTS=OFF",
        "-DPI_SSH_BUILD_SHARED=ON",
        "-DPI_SSH_OUTPUT_DIRECTORY=$piSshOutputPath",
    )
}

val buildPiSshDesktop by tasks.registering(Exec::class) {
    dependsOn(configurePiSshDesktop)
    inputs.dir(piSshSourceDir)
    outputs.file(piSshOutputDir.map { it.file(piSshLibraryName) })
    commandLine(
        "cmake",
        "--build",
        piSshBuildPath,
        "--config",
        "Release",
        "--target",
        "pi_ssh",
        "--parallel",
    )
}

val syncPiSshDesktop by tasks.registering(Sync::class) {
    dependsOn(buildPiSshDesktop)
    into(piSshResourcesDir)
    from(piSshOutputDir.map { it.file(piSshLibraryName) }) {
        into("native/$piSshClassifier")
    }
    from(piSshSourceDir.resolve("licenses")) {
        into("licenses/pi_ssh")
    }
}

sourceSets {
    main {
        resources.srcDir(piSshResourcesDir)
    }
}

tasks.named("processResources") {
    dependsOn(syncPiSshDesktop)
}

compose.desktop {
    application {
        mainClass = "io.github.yearsyan.ohpi.MainKt"
        jvmArgs += listOf("-Dohpi.app.version=$desktopAppVersion")

        buildTypes.release.proguard {
            // ProGuard 7.7 can narrow Kotlin/Okio method descriptors without
            // updating their return bytecode, producing a runtime VerifyError.
            // Keep shrinking enabled, but do not rewrite dependency bytecode.
            optimize.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "io.github.yearsyan.ohpi"
            packageVersion = desktopAppVersion
            vendor = "Oh Pi App"

            windows {
                iconFile.set(project.file("src/main/resources/icons/windows/ohpi.ico"))
            }
        }
    }
}

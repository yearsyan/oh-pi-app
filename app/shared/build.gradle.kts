import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    val piSshSourceDir = rootProject.projectDir.parentFile.resolve("native/pi_ssh")

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        val targetName = iosTarget.name
        val targetSuffix = targetName.replaceFirstChar { it.uppercase() }
        val sysroot = if (targetName == "iosArm64") "iphoneos" else "iphonesimulator"
        val nativeBuildDir = layout.buildDirectory.dir("piSsh/cmake/$targetName")
        val nativeOutputDir = layout.buildDirectory.dir("piSsh/output/$targetName")
        val nativeBuildPath = nativeBuildDir.get().asFile.absolutePath
        val nativeOutputPath = nativeOutputDir.get().asFile.absolutePath
        val configureTask =
            tasks.register<Exec>("configurePiSsh$targetSuffix") {
                inputs.dir(piSshSourceDir)
                outputs.file(nativeBuildDir.map { it.file("CMakeCache.txt") })
                commandLine(
                    "cmake",
                    "-S",
                    piSshSourceDir.absolutePath,
                    "-B",
                    nativeBuildPath,
                    "-G",
                    "Xcode",
                    "-DCMAKE_SYSTEM_NAME=iOS",
                    "-DCMAKE_OSX_SYSROOT=$sysroot",
                    "-DCMAKE_OSX_ARCHITECTURES=arm64",
                    "-DCMAKE_OSX_DEPLOYMENT_TARGET=18.5",
                    "-DPI_SSH_BUILD_SHARED=OFF",
                    "-DPI_SSH_BUILD_APPLE_BUNDLE=ON",
                    "-DPI_SSH_BUILD_TESTS=OFF",
                    "-DPI_SSH_OUTPUT_DIRECTORY=$nativeOutputPath",
                )
            }
        val buildTask =
            tasks.register<Exec>("buildPiSsh$targetSuffix") {
                dependsOn(configureTask)
                inputs.dir(piSshSourceDir)
                outputs.file(nativeOutputDir.map { it.file("libpi_ssh_bundle.a") })
                commandLine(
                    "cmake",
                    "--build",
                    nativeBuildPath,
                    "--config",
                    "Release",
                    "--target",
                    "pi_ssh_bundle",
                    "--parallel",
                )
            }

        val piSshInterop =
            iosTarget.compilations.getByName("main").cinterops.create("piSsh") {
                defFile(project.file("src/nativeInterop/cinterop/piSsh.def"))
                compilerOpts("-I${piSshSourceDir.resolve("include").absolutePath}")
                extraOpts(
                    "-libraryPath",
                    nativeOutputPath,
                    "-staticLibrary",
                    "libpi_ssh_bundle.a",
                )
            }
        tasks.matching { it.name == piSshInterop.interopProcessingTaskName }
            .configureEach { dependsOn(buildTask) }

        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    jvm()
    
    android {
       namespace = "io.github.yearsyan.ohpi.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        val androidMain by getting {
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
        }
        val jvmMain by getting {
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation(libs.ktor.client.cio)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.multiplatform.settings)
            implementation("com.squareup.okio:okio:3.4.0")
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinx.coroutines.get()}")
        }
        val jvmTest by getting {
            dependencies {
                implementation("org.jetbrains.compose.ui:ui-test-junit4:${libs.versions.composeMultiplatform.get()}")
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlin.testJunit)
            }
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

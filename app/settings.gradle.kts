rootProject.name = "PiApp"

pluginManagement {
    buildscript {
        repositories {
            maven("https://storage.googleapis.com/r8-releases/raw") {
                content {
                    includeModule("com.android.tools", "r8")
                }
            }
            mavenCentral()
        }
        dependencies {
            // Kotlin 2.4 requires R8 9.1.29 or newer.
            classpath("com.android.tools:r8:9.1.29")
        }
    }
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":androidApp")
include(":desktopApp")
include(":shared")

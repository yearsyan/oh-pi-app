import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

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
        // Kotlin 2.4 requires R8 9.1.29 or newer. Keep this Android-only so
        // desktop packaging does not resolve the Android shrinker toolchain.
        classpath("com.android.tools:r8:9.1.29")
    }
}

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "io.github.yearsyan.ohpi"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    val keystoreProperties = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreProperties.getProperty("storeFile")?.let { file(it) }
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }

    defaultConfig {
        ndkVersion = "28.2.13676358"
        applicationId = "io.github.yearsyan.ohpi"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()

        // Version rule: each dot-segment is a two-digit field, e.g. 1.10.1 → 11001
        // (major×10000 + minor×100 + patch). CI passes -PversionName from the git tag;
        // local builds fall back to the last released version.
        val releaseVersionName = providers.gradleProperty("versionName").getOrElse("2.2.3")
        val releaseVersionCode = run {
            val parts = releaseVersionName.split(".").map { it.toIntOrNull() ?: -1 }
            require(parts.size in 1..3 && parts.all { it in 0..99 }) {
                "versionName must be major[.minor[.patch]] with each part in 0..99, got: $releaseVersionName"
            }
            val major = parts[0]
            val minor = parts.getOrElse(1) { 0 }
            val patch = parts.getOrElse(2) { 0 }
            major * 10000 + minor * 100 + patch
        }
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DPI_SSH_ENABLE_JNI=ON",
                    "-DPI_SSH_BUILD_TESTS=OFF",
                    "-DPI_SSH_BUILD_SHARED=ON",
                )
                targets += "pi_ssh"
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release")
            ndk {
                abiFilters += "arm64-v8a"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        getByName("main") {
            resources.directories.add(rootProject.file("../native/pi_ssh/licenses").absolutePath)
        }
    }
    externalNativeBuild {
        cmake {
            path = rootProject.file("../native/pi_ssh/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

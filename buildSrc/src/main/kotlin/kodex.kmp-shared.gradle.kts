@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    kotlin("multiplatform")
    `maven-publish`
    id("de.infix.testBalloon")
}

group = "io.github.stream29"
version = "0.3.1"

kotlin {
    explicitApi()
    jvmToolchain(25)

    compilerOptions {
        allWarningsAsErrors.set(true)
    }

    jvm()

    js {
        browser()
        nodejs()
        binaries.library()
    }

    wasmJs {
        browser()
        nodejs()
        binaries.library()
    }

    linuxX64()
    linuxArm64()
    macosArm64()
    mingwX64()

    iosArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(
                project.extensions
                    .getByType<VersionCatalogsExtension>()
                    .named("libs")
                    .findLibrary("test-balloon-framework-core")
                    .get(),
            )
        }
    }
}

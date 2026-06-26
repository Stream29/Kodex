@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "io.github.stream29"
version = "0.1.0-SNAPSHOT"

kotlin {
    explicitApi()
    jvmToolchain(21)

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
        }
    }
}

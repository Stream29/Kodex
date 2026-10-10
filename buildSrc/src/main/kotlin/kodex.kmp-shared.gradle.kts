@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "io.github.stream29"
version = "0.4.10"

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
}

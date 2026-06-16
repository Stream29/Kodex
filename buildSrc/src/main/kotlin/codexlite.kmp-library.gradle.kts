@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "io.github.stream29"
version = "0.1.0-SNAPSHOT"

kotlin {
    explicitApi()
    jvmToolchain(26)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget("26"))
        }
    }

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

    wasmWasi {
        nodejs()
        binaries.library()
    }

    linuxX64()
    linuxArm64()
    macosArm64()
    mingwX64()

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    tvosArm64()
    tvosSimulatorArm64()

    watchosArm32()
    watchosArm64()
    watchosDeviceArm64()
    watchosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

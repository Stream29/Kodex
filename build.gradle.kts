@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
        nodejs()
        binaries.library()
    }

    wasmJs {
        nodejs()
        binaries.library()
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

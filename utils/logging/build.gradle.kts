@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    id("kodex.kmp-host")
}

kotlin {
    applyHierarchyTemplate(KotlinHierarchyTemplate.default) {
        common {
            group("fileLogging") {
                withJvm()
                withNative()
            }
        }
    }

    sourceSets {
        named("fileLoggingMain").dependencies {
            api(libs.kotlin.logging)
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(libs.kermit.core)
            implementation(libs.kermit.io)
        }
        named("fileLoggingTest").dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

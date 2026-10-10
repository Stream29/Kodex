@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
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
        commonMain.dependencies {
            api(project(":utils-logging-spec"))
        }
        named("fileLoggingMain").dependencies {
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(libs.kermit.core)
            implementation(libs.kermit.io)
        }
        named("fileLoggingTest").dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

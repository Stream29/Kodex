@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    kotlin("multiplatform")
}

// A narrow implementation layer, without narrowing the enclosing module's API targets.
kotlin {
    applyHierarchyTemplate(KotlinHierarchyTemplate.default) {
        common {
            group("cliHost") {
                withJvm()
                group("desktopNative") {
                    withLinuxX64()
                    withLinuxArm64()
                    withMacosArm64()
                    withMingwX64()
                }
            }
        }
    }
}

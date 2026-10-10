@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    applyHierarchyTemplate(KotlinHierarchyTemplate.default) {
        common {
            group("blocking") {
                withJvm()
                group("posix") {
                    withLinuxX64()
                    withLinuxArm64()
                    withMacosArm64()
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":utils-kotlinx-io-coroutines-spec"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        jsMain.dependencies {
            implementation(libs.kotlin.wrappers.node)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

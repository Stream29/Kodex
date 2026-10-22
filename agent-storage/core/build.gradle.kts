@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    applyHierarchyTemplate(KotlinHierarchyTemplate.default) {
        common {
            group("posix") {
                withLinuxX64()
                withLinuxArm64()
                withMacosArm64()
            }
        }
    }

    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(project(":agent-storage-clean-models"))
                api(project(":openai-models"))
                api(project(":utils-kotlinx-io-coroutines-system"))
                api(libs.kotlinx.serialization.json)
            }
        }
        commonMain.dependencies {
            implementation(project(":openai-json-codec"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":utils-read-write-mutex"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

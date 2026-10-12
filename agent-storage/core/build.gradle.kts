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
                api(project(":agent-storage-spec-clean-models"))
                api(project(":openai-spec-models"))
            }
        }
        commonMain.dependencies {
            api(project(":utils-kotlinx-io-coroutines-impl"))
            api(libs.kotlinx.serialization.json)
            implementation(project(":openai-spec-json-codec"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":utils-read-write-mutex"))
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

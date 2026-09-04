@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
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
        commonMain.dependencies {
            api(project(":agent-storage-contract"))
            api(project(":utils-kotlinx-io-coroutines"))
            api(libs.kotlinx.serialization.json)
            implementation(project(":openai-json-codec"))
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-contract-ext"))
        }
    }
}

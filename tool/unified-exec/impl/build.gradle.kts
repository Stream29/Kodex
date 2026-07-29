@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    id("codexlite.kmp-host")
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
            api(project(":tool-unified-exec-contract"))
            api(project(":tool-contract"))
            api(project(":tool-tool-builder"))
            api(project(":utils-shell-client"))
            implementation(libs.kotlinx.schema.json)
        }
        jsMain.dependencies {
            implementation(libs.kotlin.wrappers.node)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

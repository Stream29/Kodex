@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    id("kodex.kmp-host")
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
        commonMain.dependencies {
            api(project(":tool-spec-unified-exec"))
            api(project(":tool-spec-contract"))
            api(project(":utils-shell-client-impl"))
            api(libs.kotlinx.schema.json)
        }
        jsMain.dependencies {
            implementation(libs.kotlin.wrappers.node)
        }
        commonTest.dependencies {
            implementation(project(":tool-impl-builder"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

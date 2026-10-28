@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
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
        named("specMain") {
            dependencies {
                api(project(":tool-unified-exec-contract"))
                api(project(":tool-core"))
                api(libs.kotlinx.schema.json)
                api(project(":utils-shell-client"))
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.io.core)
            }
        }
        commonMain.dependencies {
            implementation(project(":utils-shell-client-system"))
        }
        commonTest.dependencies {
            implementation(project(":tool-core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

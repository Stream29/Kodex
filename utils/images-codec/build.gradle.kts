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
            group("skikoNative") {
                withLinuxX64()
                withLinuxArm64()
                withMacosArm64()
            }
        }
    }

    sourceSets {
        named("skikoNativeMain") {
            dependencies {
                implementation(libs.skiko)
            }
        }

        commonMain.dependencies {
            api(project(":utils-images"))
            api(project(":utils-kotlinx-io-coroutines-system"))
            implementation(libs.korim)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":utils-images"))
                api(project(":utils-kotlinx-io-coroutines"))
                api(libs.kotlinx.io.core)
            }
        }
    }
}

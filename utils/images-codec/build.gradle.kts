@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("codexlite.kmp-host")
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

    targets.withType<KotlinNativeTarget>().configureEach {
        if (name == "mingwX64") {
            binaries.configureEach {
                linkerOpts("-lole32")
            }
        }
    }

    sourceSets {
        val skikoNativeMain by getting {
            dependencies {
                implementation(libs.skiko)
            }
        }
        val skikoNativeTest by getting

        commonMain.dependencies {
            api(project(":utils:images"))
            api(project(":utils:kotlinx-io-coroutines"))
            implementation(libs.korim)
        }
        jvmMain.dependencies {
            implementation(libs.twelvemonkeys.imageio.jpeg)
        }
        jsMain.dependencies {
            implementation(libs.kotlin.wrappers.node)
            implementation(npm("sharp", libs.versions.sharp.get()))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

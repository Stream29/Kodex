@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-shared")
    id("kodex.kmp-tests")
}

kotlin {
    // Preserve the former Host/CLI implementation target options after consolidation.
    mingwX64 {
        binaries.all { linkerOpts("-lole32") }
    }
    js {
        nodejs {
            testTask {
                useMocha { timeout = "120s" }
            }
        }
    }
}

kotlin {
    applyHierarchyTemplate(KotlinHierarchyTemplate.default) {
        common {
            group("host") {
                withJs()
                group("lucene") {
                    withJvm()
                    withLinuxX64()
                    withLinuxArm64()
                    withMacosArm64()
                    withMingwX64()
                }
            }
        }
    }

    sourceSets {
        val luceneMain by getting {
            dependencies {
                implementation(libs.lucene.kmp.core)
            }
        }
    }
}

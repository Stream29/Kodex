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
            group("fileLogging") {
                withJvm()
                withLinuxX64()
                withLinuxArm64()
                withMacosArm64()
                withMingwX64()
            }
        }
    }

    sourceSets {
        named("fileLoggingMain").dependencies {
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(libs.kermit.core)
            implementation(libs.kermit.io)
        }
        named("fileLoggingTest").dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlin.logging)
        }
    }
}

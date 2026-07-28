@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("codexlite.kmp-cli")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

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
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            implementation(project(":utils-coroutines"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        if (konanTarget.family == Family.LINUX || konanTarget.family == Family.OSX) {
            compilations.named("main") {
                cinterops.create("processClientSpawn") {
                    defFile(project.file("src/nativeInterop/cinterop/process_client_spawn.def"))
                    if (konanTarget.family == Family.LINUX) {
                        compilerOpts("-D_GNU_SOURCE")
                    }
                }
            }
        }
    }
}

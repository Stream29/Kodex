@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
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
        named("specMain").dependencies {
            api(project(":utils-process-client"))
            api(libs.kotlinx.coroutines.core)
        }
        commonMain.dependencies {
            api(libs.kotlinx.io.core)
            api(project(":utils-kotlinx-io-coroutines-system"))
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
                    compilerOpts("-I${rootProject.file("utils/posix-fd").absolutePath}")
                    if (konanTarget.family == Family.LINUX) {
                        compilerOpts("-D_GNU_SOURCE")
                    }
                }
            }
        }
        if (konanTarget.family == Family.MINGW) {
            compilations.named("main") {
                cinterops.create("processClientWindows") {
                    defFile(project.file("src/nativeInterop/cinterop/process_client_windows.def"))
                    compilerOpts("-D_WIN32_WINNT=0x0600")
                }
            }
        }
    }
}

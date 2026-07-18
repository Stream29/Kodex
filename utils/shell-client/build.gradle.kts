@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("codexlite.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

rootProject.extensions.configure<YarnRootExtension> {
    // node-pty builds its Linux native binding during its pinned npm install.
    ignoreScripts = false
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
            api(libs.kotlinx.serialization.core)
            implementation(project(":utils-os-environment"))
        }
        jsMain.dependencies {
            implementation(libs.kotlin.wrappers.node)
            implementation(npm("node-pty", libs.versions.node.pty.get()))
        }
        jvmMain.dependencies {
            implementation(libs.pty4j)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":utils-kotlinx-io-coroutines"))
        }
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        if (konanTarget.family == Family.LINUX || konanTarget.family == Family.OSX) {
            if (konanTarget.family == Family.LINUX) {
                binaries.all {
                    linkerOpts("-lutil")
                }
            }
            compilations.named("main") {
                cinterops.create("shellClientSpawn") {
                    defFile(project.file("src/nativeInterop/cinterop/shell_client_spawn.def"))
                }
            }
        }
        if (konanTarget.family == Family.MINGW) {
            compilations.named("main") {
                cinterops.create("shellClientConPty") {
                    defFile(project.file("src/nativeInterop/cinterop/shell_client_conpty.def"))
                }
            }
        }
    }
}

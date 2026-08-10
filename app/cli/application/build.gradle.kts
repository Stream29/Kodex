import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    applyDefaultHierarchyTemplate()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            entryPoint = "io.github.stream29.kodex.cli.app.main"
        }
    }

    sourceSets {
        val commonMain by getting
        val commonTest by getting
        val jvmMain by getting
        val jvmTest by getting
        val linuxX64Main by getting
        val linuxX64Test by getting
        val linuxArm64Main by getting
        val linuxArm64Test by getting
        val macosArm64Main by getting
        val macosArm64Test by getting
        val mingwX64Main by getting
        val mingwX64Test by getting

        commonMain.dependencies {
            implementation(project(":app-shared-application"))
            implementation(project(":utils-logging"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-contract"))
            implementation(project(":app-shared-auth-contract"))
            implementation(project(":app-shared-settings-contract"))
            implementation(libs.kotlinx.coroutines.test)
        }
        val mosaicMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(project(":openai-account-usage-contract"))
                implementation(project(":agent-state-contract"))
                implementation(project(":app-shared-agent"))
                implementation(project(":app-shared-auth-contract"))
                implementation(project(":app-shared-new-session"))
                implementation(project(":app-shared-session"))
                implementation(project(":app-shared-session-title"))
                implementation(project(":app-shared-settings-contract"))
                implementation(project(":app-shared-settings-login"))
                implementation(project(":app-cli-agent"))
                implementation(project(":app-cli-components"))
                implementation(project(":app-cli-history"))
                implementation(project(":app-cli-path-picker"))
                implementation(project(":app-cli-settings-login"))
                implementation(project(":mcp-contract"))
                implementation(project(":openai-models"))
                implementation(project(":utils-coroutines"))
                implementation(project(":utils-kodex-home"))
                implementation(project(":utils-logging"))
                implementation(project(":utils-os-environment"))
                implementation(project(":utils-terminal-text"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.io.core)
                implementation(libs.mosaic.animation)
                implementation(libs.mosaic.runtime)
            }
        }
        val mosaicTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(libs.mosaic.testing)
            }
        }

        jvmMain.dependsOn(mosaicMain)
        linuxX64Main.dependsOn(mosaicMain)
        linuxArm64Main.dependsOn(mosaicMain)
        macosArm64Main.dependsOn(mosaicMain)
        mingwX64Main.dependsOn(mosaicMain)

        jvmTest.dependsOn(mosaicTest)
        linuxX64Test.dependsOn(mosaicTest)
        linuxArm64Test.dependsOn(mosaicTest)
        macosArm64Test.dependsOn(mosaicTest)
        mingwX64Test.dependsOn(mosaicTest)
    }
}

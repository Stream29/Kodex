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
            implementation(project(":cli-new-session"))
            implementation(project(":cli-session"))
            implementation(project(":cli-auth-filesystem"))
            implementation(project(":cli-settings-filesystem"))
            implementation(project(":agent-session-contract"))
            implementation(project(":agent-session-filesystem"))
            implementation(project(":openai-client"))
            implementation(project(":openai-codex-cli-storage"))
            implementation(project(":openai-model-catalog-impl"))
            implementation(project(":openai-models"))
            implementation(project(":cli-session-title"))
            implementation(project(":mcp-contract"))
            implementation(project(":mcp-impl"))
            implementation(project(":hook-impl"))
            implementation(project(":utils-kodex-home"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-os-environment"))
            implementation(libs.koin.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":cli-auth-contract"))
            implementation(project(":cli-settings-contract"))
            implementation(project(":openai-client-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        val mosaicMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(project(":cli-session"))
                implementation(project(":cli-components"))
                implementation(project(":cli-history"))
                implementation(project(":cli-path-picker"))
                implementation(project(":cli-settings-login"))
                implementation(project(":utils-logging"))
                implementation(project(":utils-terminal-text"))
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

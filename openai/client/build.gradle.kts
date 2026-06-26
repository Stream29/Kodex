plugins {
    id("codexlite.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(project(":openai:client-contract"))
            api(project(":openai:models"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.sse)
        }
        jvmMain.dependencies {
            implementation(libs.bundles.ktor.client.jvm.engines)
        }
        jsMain.dependencies {
            implementation(libs.bundles.ktor.client.js.engines)
        }
        linuxX64Main.dependencies {
            implementation(libs.bundles.ktor.client.linux.engines)
        }
        linuxArm64Main.dependencies {
            implementation(libs.bundles.ktor.client.linux.engines)
        }
        macosArm64Main.dependencies {
            implementation(libs.bundles.ktor.client.macos.engines)
        }
        mingwX64Main.dependencies {
            implementation(libs.bundles.ktor.client.mingw.engines)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":openai:codex-cli-storage"))
            implementation(project(":utils:host-test-support"))
        }
    }
}

plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":cli-auth-contract"))
            api(project(":cli-settings-contract"))
            implementation(project(":openai-client"))
            implementation(project(":openai-codex-cli-storage"))
            implementation(project(":openai-json-codec"))
            implementation(project(":openai-models"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(libs.kaml)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.utils)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

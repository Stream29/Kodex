plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-shared-auth-contract"))
            api(project(":app-shared-settings-contract"))
            api(project(":utils-kotlinx-io-coroutines-impl"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":openai-impl-client"))
            implementation(project(":openai-impl-codex-cli-storage"))
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":openai-spec-models"))
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-logging-impl"))
            implementation(libs.kaml)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.utils)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

plugins {
    id("codexlite.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(project(":openai-client"))
            api(project(":openai-models"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.io.core)
            implementation(libs.ktor.client.core)
            implementation(project(":openai-codex-cli-storage"))
            implementation(project(":utils-os-environment"))
        }
    }
}

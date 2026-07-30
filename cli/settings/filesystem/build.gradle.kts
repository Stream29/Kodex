plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":cli-settings-contract"))
            api(project(":openai-codex-cli-storage"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(libs.kaml)
        }
    }
}

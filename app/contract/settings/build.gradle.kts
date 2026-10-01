plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-contract-path-picker"))
            api(project(":app-contract-session"))
            api(project(":app-shared-settings-contract"))
            api(project(":rpc-spec-models"))
            api(project(":mcp-spec-contract"))
            api(project(":openai-spec-account-usage"))
            api(project(":openai-spec-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.serialization.core)
        }
    }
}

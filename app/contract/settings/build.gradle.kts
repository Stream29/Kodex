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
            api(project(":hook-contract"))
            api(project(":mcp-contract"))
            api(project(":openai-account-usage-contract"))
            api(project(":openai-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.serialization.core)
        }
    }
}

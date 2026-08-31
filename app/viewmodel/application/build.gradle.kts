plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":agent-session-contract"))
            implementation(project(":agent-session-filesystem"))
            implementation(project(":agent-state-contract"))
            implementation(project(":app-contract-application"))
            implementation(project(":app-contract-session"))
            implementation(project(":app-contract-session-catalog"))
            implementation(project(":app-contract-settings"))
            implementation(project(":app-viewmodel-history"))
            implementation(project(":app-viewmodel-new-session"))
            implementation(project(":app-viewmodel-agent"))
            implementation(project(":app-viewmodel-session"))
            implementation(project(":app-shared-session-title"))
            implementation(project(":mcp-contract"))
            implementation(project(":openai-account-usage-contract"))
            implementation(project(":openai-models"))
            implementation(project(":app-shared-auth-filesystem"))
            implementation(project(":app-shared-settings-filesystem"))
            implementation(project(":app-viewmodel-settings"))
            implementation(project(":app-viewmodel-path-picker"))
            implementation(project(":openai-client"))
            implementation(project(":openai-account-usage-impl"))
            implementation(project(":openai-codex-cli-storage"))
            implementation(project(":openai-model-catalog-impl"))
            implementation(project(":mcp-impl"))
            implementation(project(":hook-impl"))
            implementation(project(":utils-kodex-home"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-logging"))
            implementation(project(":utils-os-environment"))
            implementation(libs.koin.core)
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-contract-ext"))
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":app-shared-auth-contract"))
            implementation(project(":app-shared-settings-contract"))
            implementation(project(":openai-client-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

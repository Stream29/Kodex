plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-session-contract"))
            api(project(":agent-session-filesystem"))
            api(project(":app-shared-new-session"))
            api(project(":app-shared-session"))
            api(project(":app-shared-session-title"))
            api(project(":openai-account-usage-contract"))
            api(project(":openai-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            implementation(project(":app-shared-auth-filesystem"))
            implementation(project(":app-shared-settings-filesystem"))
            implementation(project(":openai-client"))
            implementation(project(":openai-account-usage-impl"))
            implementation(project(":openai-codex-cli-storage"))
            implementation(project(":openai-model-catalog-impl"))
            implementation(project(":mcp-contract"))
            implementation(project(":mcp-impl"))
            implementation(project(":hook-impl"))
            implementation(project(":utils-kodex-home"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-logging"))
            implementation(project(":utils-os-environment"))
            implementation(libs.koin.core)
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":app-shared-auth-contract"))
            implementation(project(":app-shared-settings-contract"))
            implementation(project(":openai-client-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

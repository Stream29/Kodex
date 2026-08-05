plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":app-shared-new-session"))
            implementation(project(":app-shared-session"))
            implementation(project(":app-shared-auth-filesystem"))
            implementation(project(":app-shared-settings-filesystem"))
            implementation(project(":agent-session-contract"))
            implementation(project(":agent-session-filesystem"))
            implementation(project(":openai-client"))
            implementation(project(":openai-codex-cli-storage"))
            implementation(project(":openai-model-catalog-impl"))
            implementation(project(":openai-models"))
            implementation(project(":app-shared-session-title"))
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
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
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

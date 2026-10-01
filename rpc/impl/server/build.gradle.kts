plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-session-contract"))
            api(project(":utils-rpc-exception-spec"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":utils-coroutines-spec"))
            api(project(":app-shared-settings-filesystem"))
            api(project(":mcp-impl-composition"))
            api(project(":openai-impl-model-catalog"))
            implementation(project(":openai-impl-codex-cli-storage"))
            api(project(":app-shared-auth-filesystem"))
            api(project(":openai-impl-account-usage"))
            implementation(libs.ktor.http)
            api(project(":app-contract-settings"))
            api(project(":rpc-spec-contract"))
            implementation(project(":app-shared-session-title"))
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(project(":agent-session-filesystem"))
            implementation(project(":openai-impl-client"))
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-filesystem"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":rpc-impl-in-memory"))
            implementation(project(":rpc-impl-client"))
        }
    }
}

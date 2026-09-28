plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-session-contract"))
            api(project(":utils-rpc-exception"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":utils-coroutines"))
            api(project(":app-shared-settings-filesystem"))
            api(project(":mcp-impl"))
            api(project(":openai-model-catalog-impl"))
            implementation(project(":openai-codex-cli-storage"))
            api(project(":app-shared-auth-filesystem"))
            api(project(":openai-account-usage-impl"))
            implementation(libs.ktor.http)
            api(project(":app-contract-settings"))
            api(project(":rpc-contract"))
            implementation(project(":app-shared-session-title"))
            implementation(project(":agent-storage-contract-ext"))
            implementation(project(":agent-session-filesystem"))
            implementation(project(":openai-client"))
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-filesystem"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-contract-ext"))
            implementation(project(":openai-client-test"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":rpc-in-memory"))
            implementation(project(":rpc-client"))
        }
    }
}

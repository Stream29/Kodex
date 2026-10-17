plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-session-contract"))
            api(project(":utils-rpc-exception"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":utils-coroutines"))
            api(project(":app-settings-filesystem"))
            api(project(":mcp"))
            api(project(":openai-model-catalog"))
            implementation(project(":openai-codex-cli-storage"))
            api(project(":openai-account-usage"))
            implementation(libs.ktor.http)
            implementation(libs.ktor.utils)
            api(project(":utils-kotlinx-io-coroutines-system"))
            implementation(project(":utils-logging"))
            implementation(project(":utils-shell-client-system"))
            implementation(project(":agent-state-contract"))
            api(project(":rpc-client"))
            implementation(project(":agent-storage-core"))
            implementation(project(":agent-session-filesystem"))
            implementation(project(":openai-client"))
            implementation(project(":openai-json-codec"))
            implementation(project(":openai-models"))
            implementation(libs.kaml)
            implementation(libs.kotlinx.schema.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-filesystem"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-core"))
            implementation(project(":openai-client-test"))
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":rpc-in-memory"))
            implementation(project(":rpc-client"))
            implementation(project(":app-rpc"))
        }
    }
}

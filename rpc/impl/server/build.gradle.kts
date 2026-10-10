plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-session-spec-contract"))
            api(project(":utils-rpc-exception-spec"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":utils-coroutines-spec"))
            api(project(":app-settings-spec-persistence"))
            implementation(project(":app-settings-impl-filesystem"))
            api(project(":mcp-impl-composition"))
            api(project(":openai-impl-model-catalog"))
            implementation(project(":openai-impl-codex-cli-storage"))
            api(project(":openai-impl-account-usage"))
            implementation(libs.ktor.http)
            implementation(libs.ktor.utils)
            api(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(project(":utils-logging-impl"))
            implementation(project(":utils-shell-client-impl"))
            implementation(project(":agent-state-spec-contract"))
            api(project(":rpc-spec-contract"))
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(project(":agent-session-impl-filesystem"))
            implementation(project(":openai-impl-client"))
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":openai-spec-models"))
            implementation(libs.kaml)
            implementation(libs.kotlinx.schema.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":agent-session-impl-in-memory"))
            implementation(project(":agent-session-impl-filesystem"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":rpc-impl-in-memory"))
            implementation(project(":rpc-impl-client"))
            implementation(project(":app-impl-rpc"))
        }
    }
}

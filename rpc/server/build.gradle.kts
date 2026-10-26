plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-rpc-protocol"))
            api(project(":agent-session-contract"))
            api(project(":agent-state-contract"))
            api(project(":agent-storage-clean-models"))
            api(project(":agent-storage-core"))
            api(project(":tool-multi-agent"))
            api(project(":rpc-client"))
            api(project(":openai-models"))
            api(project(":openai-client"))
            api(project(":openai-account-usage"))
            api(project(":app-backend-settings"))
            api(project(":mcp-backend"))
            api(project(":openai-model-catalog"))
            api(libs.kotlinx.rpc.core)
            api(project(":utils-kotlinx-io-coroutines-system"))
            api(libs.kotlinx.coroutines.core)
        }
        commonMain.dependencies {
            implementation(project(":openai-account-usage-backend"))
            api(project(":utils-rpc-exception"))
            implementation(project(":utils-coroutines"))
            implementation(project(":openai-codex-cli-storage"))
            implementation(libs.ktor.http)
            implementation(libs.ktor.utils)
            implementation(project(":utils-logging-backend-scope"))
            implementation(project(":utils-shell-client-system"))
            implementation(project(":agent-session-filesystem"))
            implementation(project(":openai-json-codec"))
            implementation(libs.kaml)
            implementation(libs.kotlinx.schema.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":app-rpc-system"))
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

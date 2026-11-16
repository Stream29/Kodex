plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":rpc-client"))
            api(project(":openai-client"))
            api(project(":app-backend-settings"))
            api(libs.kotlinx.rpc.core)
            api(libs.kotlinx.coroutines.core)
        }
        commonMain.dependencies {
            api(project(":utils-rpc-exception"))
            implementation(project(":app-rpc-protocol"))
            implementation(project(":agent-eventbus"))
            implementation(project(":agent-schedule-contract"))
            implementation(project(":agent-session-contract"))
            implementation(project(":agent-state-contract"))
            implementation(project(":agent-storage-clean-models"))
            implementation(project(":agent-storage-core"))
            implementation(project(":openai-models"))
            implementation(project(":openai-account-usage"))
            implementation(project(":mcp-backend"))
            implementation(project(":openai-model-catalog"))
            implementation(project(":tool-image-generation"))
            implementation(project(":tool-web-run"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":hook-notification"))
            implementation(project(":utils-oauth-loopback"))
            implementation(project(":utils-os-environment"))
            implementation(project(":openai-account-usage-backend"))
            implementation(project(":utils-coroutines"))
            implementation(project(":openai-codex-cli-storage"))
            implementation(libs.ktor.http)
            implementation(libs.ktor.utils)
            implementation(project(":utils-logging-backend-scope"))
            implementation(project(":utils-shell-client-system"))
            implementation(project(":agent-schedule-sqlite"))
            implementation(project(":agent-session-sqlite"))
            implementation(project(":openai-json-codec"))
            implementation(libs.kaml)
            implementation(libs.kotlinx.schema.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":agent-session-sqlite"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-core"))
            implementation(project(":openai-client-test"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":rpc-in-memory"))
            implementation(project(":rpc-client"))
            implementation(project(":app-rpc"))
        }
        nativeTest.dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.server.cio)
        }
    }
}

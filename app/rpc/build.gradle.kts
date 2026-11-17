plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-frontend")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-rpc-protocol"))
            api(project(":agent-state-contract"))
            api(project(":agent-storage-clean-models"))
            api(project(":agent-storage-core"))
            api(project(":app-component-agent-contract"))
            api(project(":app-component-application-viewmodel"))
            api(project(":app-component-session-catalog-viewmodel"))
            api(project(":app-component-session-contract"))
            api(project(":app-component-settings-viewmodel"))
            api(project(":mcp-contract"))
            api(project(":openai-models"))
            api(project(":rpc-client"))
            api(project(":rpc-connection"))
            api(project(":rpc-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.rpc.core)
        }
        commonMain.dependencies {
            implementation(project(":app-component-composer-viewmodel"))
            implementation(project(":app-component-history-index-viewmodel"))
            implementation(project(":app-component-history-viewmodel"))
            implementation(project(":app-component-path-picker-viewmodel"))
            implementation(project(":app-component-request-user-input-viewmodel"))
            implementation(project(":app-component-runtime-configuration-viewmodel"))
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        nativeTest.dependencies {
            implementation(project(":app-backend-settings"))
            implementation(project(":rpc-server"))
            implementation(project(":rpc-in-memory"))
            implementation(project(":openai-client-test"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.http)
        }
    }
}

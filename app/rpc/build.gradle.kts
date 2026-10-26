plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-frontend")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":agent-state-contract"))
            api(project(":agent-storage-clean-models"))
            api(project(":agent-storage-core"))
            api(project(":app-component-account-usage-viewmodel"))
            api(project(":app-component-agent-contract"))
            api(project(":app-component-application-preferences-viewmodel"))
            api(project(":app-component-authentication-settings-viewmodel"))
            api(project(":app-component-context-source-settings-viewmodel"))
            api(project(":app-component-hook-settings-viewmodel"))
            api(project(":app-component-mcp-settings-viewmodel"))
            api(project(":app-component-new-session-defaults-viewmodel"))
            api(project(":app-component-openai-login-viewmodel"))
            api(project(":app-component-runtime-configuration-viewmodel"))
            api(project(":app-component-session-catalog-viewmodel"))
            api(project(":app-component-session-settings-viewmodel"))
            api(project(":app-component-session-title-settings-viewmodel"))
            api(project(":app-component-settings-viewmodel"))
            api(project(":app-component-usage-reset-viewmodel"))
            api(project(":mcp-contract"))
            api(project(":openai-models"))
            api(project(":rpc-client"))
            api(project(":app-component-new-session-viewmodel"))
            api(project(":rpc-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.rpc.core)
        }
        commonMain.dependencies {
            implementation(project(":rpc-connection"))
            implementation(project(":app-component-composer-viewmodel"))
            implementation(project(":app-component-history-index-viewmodel"))
            implementation(project(":app-component-request-user-input-viewmodel"))
            implementation(project(":app-component-suggest-subagent-task-viewmodel"))
            implementation(project(":app-component-history-viewmodel"))
            implementation(libs.kotlin.logging)
            implementation(libs.ktor.http)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        nativeTest.dependencies {
            implementation(project(":app-rpc-system"))
            implementation(project(":app-backend-settings"))
            implementation(project(":app-component-session-settings-viewmodel"))
            implementation(project(":rpc-server"))
            implementation(project(":rpc-in-memory"))
            implementation(project(":openai-client-test"))
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.cio)
        }
    }
}

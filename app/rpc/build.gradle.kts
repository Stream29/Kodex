plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-viewmodel")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":app-component-composer-viewmodel"))
            implementation(project(":app-component-new-session-defaults-viewmodel"))
            implementation(project(":app-component-runtime-configuration-viewmodel"))
            implementation(project(":app-component-history-index-viewmodel"))
            implementation(project(":app-component-context-source-settings-viewmodel"))
            implementation(project(":app-component-session-title-settings-viewmodel"))
            implementation(project(":app-component-application-preferences-viewmodel"))
            implementation(project(":app-component-authentication-settings-viewmodel"))
            implementation(project(":app-component-account-usage-viewmodel"))
            implementation(project(":app-component-usage-reset-viewmodel"))
            implementation(project(":utils-os-environment"))
            implementation(project(":app-component-request-user-input-viewmodel"))
            implementation(project(":app-component-suggest-subagent-task-viewmodel"))
            implementation(project(":app-component-hook-settings-viewmodel"))
            implementation(project(":app-component-mcp-settings-viewmodel"))
            api(project(":app-component-openai-login-viewmodel"))
            implementation(project(":app-component-openai-login-viewmodel"))
            implementation(project(":app-component-session-catalog-viewmodel"))
            api(project(":rpc-client"))
            api(project(":app-component-history-viewmodel"))
            api(project(":app-component-agent-contract"))
            api(project(":agent-state-contract"))
            api(project(":app-component-settings-viewmodel"))
            implementation(libs.kotlin.logging)
            implementation(project(":utils-logging"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.core)
        }
        commonTest.dependencies {
            implementation(project(":app-settings-filesystem"))
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

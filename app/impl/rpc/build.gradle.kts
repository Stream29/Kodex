plugins {
    id("kodex.kmp-viewmodel")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":app-component-composer-impl-viewmodel"))
            implementation(project(":app-component-new-session-defaults-impl-viewmodel"))
            implementation(project(":app-component-runtime-configuration-impl-viewmodel"))
            implementation(project(":app-component-history-index-impl-viewmodel"))
            implementation(project(":app-component-context-source-settings-impl-viewmodel"))
            implementation(project(":app-component-session-title-settings-impl-viewmodel"))
            implementation(project(":app-component-application-preferences-impl-viewmodel"))
            implementation(project(":app-component-authentication-settings-impl-viewmodel"))
            implementation(project(":app-component-account-usage-impl-viewmodel"))
            implementation(project(":app-component-usage-reset-impl-viewmodel"))
            implementation(project(":app-component-session-title-settings-spec"))
            implementation(project(":utils-os-environment-spec"))
            implementation(project(":app-component-request-user-input-impl-viewmodel"))
            implementation(project(":app-component-suggest-subagent-task-impl-viewmodel"))
            implementation(project(":app-component-hook-settings-impl-viewmodel"))
            implementation(project(":app-component-mcp-settings-impl-viewmodel"))
            api(project(":app-component-openai-login-spec"))
            implementation(project(":app-component-openai-login-impl-viewmodel"))
            implementation(project(":app-component-session-catalog-impl-viewmodel"))
            api(project(":rpc-impl-client"))
            api(project(":app-component-history-impl-viewmodel"))
            api(project(":app-component-agent-spec"))
            api(project(":agent-state-spec-contract"))
            api(project(":app-component-settings-spec"))
            implementation(project(":app-component-mcp-settings-spec"))
            api(project(":app-settings-spec-persistence"))
            implementation(libs.kotlin.logging)
            implementation(project(":utils-logging-impl"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.core)
        }
        commonTest.dependencies {
            implementation(project(":app-settings-impl-filesystem"))
            implementation(project(":app-component-session-settings-impl-viewmodel"))
            implementation(project(":rpc-impl-server"))
            implementation(project(":rpc-impl-in-memory"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.cio)
        }
    }
}

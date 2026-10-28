plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-web-executable")
    id("kodex.kmp-tests")
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "kodex-renderer-smoke.js" }
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(project(":app-component-widgets"))
            implementation(project(":app-component-session-delete-view"))
            implementation(project(":app-component-session-rename-view"))
            implementation(project(":app-component-composer-view"))
            implementation(project(":app-component-new-session-view"))
            implementation(project(":app-component-history-view"))
            implementation(project(":app-component-session-viewmodel"))
            implementation(project(":app-component-application-viewmodel"))
            implementation(project(":app-component-application-view"))
            implementation(project(":app-rpc"))
            implementation(project(":app-rpc-application"))
            implementation(project(":rpc-client"))
            implementation(project(":rpc-websocket"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        wasmJsMain.dependencies {
            implementation(project(":app-web-renderer"))
            implementation(project(":app-component-hook-settings-view"))
            implementation(compose.material3)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlinx.rpc.krpc.client)
            implementation(libs.kotlinx.rpc.krpc.serialization.json)
        }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
        wasmJsTest.dependencies {
            implementation(project(":app-component-session-tab-bar-view"))
            implementation(project(":app-component-agent-view"))
            implementation(project(":app-component-session-sidebar-view"))
            implementation(project(":app-component-history-index-view"))
            implementation(libs.navigation.event)
            implementation(project(":app-component-account-usage-view"))
            implementation(project(":app-component-usage-reset-view"))
            implementation(project(":app-component-application-preferences-view"))
            implementation(project(":app-component-session-title-settings-view"))
            implementation(project(":app-component-authentication-settings-view"))
            implementation(project(":app-component-openai-login-view"))
            implementation(project(":app-component-new-session-defaults-view"))
            implementation(project(":app-component-session-settings-view"))
            implementation(project(":app-component-context-source-settings-view"))
            implementation(project(":app-component-hook-settings-view"))
            implementation(project(":app-component-mcp-settings-view"))
            implementation(project(":app-component-path-picker-view"))
            implementation(project(":app-component-working-directory-view"))
            implementation(project(":app-component-session-catalog-view"))
            implementation(project(":app-component-settings-view"))
            implementation(project(":app-component-request-user-input-view"))
            implementation(project(":app-component-suggest-subagent-task-view"))
            implementation(project(":agent-storage-core"))
            implementation(project(":agent-state-contract"))
            implementation(project(":app-component-history-viewmodel"))
        }
    }
}

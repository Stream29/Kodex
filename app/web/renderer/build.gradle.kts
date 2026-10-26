plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-tests")
    id("kodex.kmp-graphical-view")
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-usage-reset-view"))
            api(project(":app-component-session-settings-view"))
            api(project(":app-component-openai-login-view"))
            api(project(":app-component-context-source-settings-view"))
            api(project(":app-component-authentication-settings-view"))
            api(project(":app-component-account-usage-view"))
            api(project(":app-component-application-view"))
            api(project(":app-component-session-tab-bar-view"))
            api(project(":app-component-agent-view"))
            api(project(":app-component-session-sidebar-view"))
            api(project(":app-component-history-index-view"))
            api(project(":app-component-widgets"))
            api(project(":app-component-runtime-configuration-view"))
            api(project(":app-component-hook-settings-view"))
            api(project(":app-component-mcp-settings-view"))
            api(project(":app-component-path-picker-view"))
            api(project(":app-component-session-catalog-view"))
            api(project(":app-component-session-delete-view"))
            api(project(":app-component-settings-view"))
            api(project(":app-component-request-user-input-view"))
            api(project(":app-component-suggest-subagent-task-view"))
            api(project(":app-component-history-view"))
            api(compose.runtime)
        }
        named("composeUiMain").dependencies {
            implementation(project(":app-web-theme"))
            implementation(project(":app-component-new-session-view"))
            implementation(project(":app-component-openai-login-view"))
            implementation(libs.navigation.event.compose)
            implementation(libs.kotlinx.datetime)
            implementation(project(":app-component-session-rename-view"))
            implementation(project(":app-component-working-directory-view"))
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        wasmJsMain.dependencies {
            implementation(libs.navigation.event)
            implementation(libs.navigation.event.compose)
        }
        wasmJsTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

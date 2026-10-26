plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-component-view")
    id("kodex.kmp-tests")
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-settings-viewmodel"))
            api(project(":app-component-widgets"))
            api(project(":app-component-session-rename-viewmodel"))
            api(project(":app-component-working-directory-viewmodel"))
            api(project(":app-component-hook-settings-view"))
            api(project(":app-component-mcp-settings-view"))
            api(compose.runtime)
        }
        commonMain.dependencies {
            implementation(project(":app-component-session-settings-view"))
            implementation(project(":app-component-new-session-defaults-view"))
            implementation(project(":app-component-context-source-settings-view"))
            implementation(project(":app-component-session-title-settings-view"))
            implementation(project(":app-component-application-preferences-view"))
            implementation(project(":app-component-authentication-settings-view"))
            implementation(project(":app-component-account-usage-view"))
            implementation(project(":app-component-usage-reset-view"))
            implementation(project(":app-component-hook-settings-view"))
            implementation(project(":app-component-mcp-settings-view"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-session-settings-spec"))
            api(project(":app-component-new-session-defaults-spec"))
            api(project(":app-component-context-source-settings-spec"))
            api(project(":app-component-session-title-settings-spec"))
            api(project(":app-component-application-preferences-spec"))
            api(project(":app-component-authentication-settings-spec"))
            api(project(":app-component-account-usage-spec"))
            api(project(":app-component-usage-reset-spec"))
            api(project(":app-component-hook-settings-spec"))
            api(project(":app-component-mcp-settings-spec"))
            api(project(":app-component-working-directory-spec"))
            api(project(":app-component-path-picker-spec"))
            api(project(":app-contract-session"))
            api(project(":rpc-spec-models"))
            api(project(":mcp-spec-contract"))
            api(project(":openai-spec-account-usage"))
            api(project(":openai-spec-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.serialization.core)
        }
    }
}

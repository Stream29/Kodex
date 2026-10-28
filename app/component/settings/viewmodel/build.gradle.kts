plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-frontend")
    id("kodex.kmp-tests")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-session-settings-viewmodel"))
            api(project(":app-component-new-session-defaults-viewmodel"))
            api(project(":app-component-context-source-settings-viewmodel"))
            api(project(":app-component-session-title-settings-viewmodel"))
            api(project(":app-component-application-preferences-viewmodel"))
            api(project(":app-component-authentication-settings-viewmodel"))
            api(project(":app-component-account-usage-viewmodel"))
            api(project(":app-component-usage-reset-viewmodel"))
            api(project(":app-component-hook-settings-viewmodel"))
            api(project(":app-component-mcp-settings-viewmodel"))
            api(project(":app-component-working-directory-viewmodel"))
            api(project(":app-component-path-picker-viewmodel"))
            api(project(":app-component-session-contract"))
            api(project(":rpc-models"))
            api(project(":mcp-contract"))
            api(project(":openai-account-usage"))
            api(project(":openai-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.serialization.core)
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        nativeTest.dependencies {
            implementation(project(":app-rpc-system"))
            implementation(project(":app-backend-settings"))
            implementation(project(":app-component-session-settings-viewmodel"))
            implementation(project(":app-rpc-test-support"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

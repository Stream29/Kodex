plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-viewmodel")
    id("kodex.kmp-tests")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-session-settings-impl-viewmodel"))
            api(project(":app-component-new-session-defaults-impl-viewmodel"))
            api(project(":app-component-context-source-settings-impl-viewmodel"))
            api(project(":app-component-session-title-settings-impl-viewmodel"))
            api(project(":app-component-application-preferences-impl-viewmodel"))
            api(project(":app-component-authentication-settings-impl-viewmodel"))
            api(project(":app-component-account-usage-impl-viewmodel"))
            api(project(":app-component-usage-reset-impl-viewmodel"))
            api(project(":app-component-hook-settings-impl-viewmodel"))
            api(project(":app-component-mcp-settings-impl-viewmodel"))
            api(project(":app-component-working-directory-impl-viewmodel"))
            api(project(":app-component-path-picker-impl-viewmodel"))
            api(project(":app-spec-session"))
            api(project(":rpc-spec-models"))
            api(project(":mcp-spec-contract"))
            api(project(":openai-account-usage"))
            api(project(":openai-spec-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.serialization.core)
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":app-settings-impl-filesystem"))
            implementation(project(":app-component-session-settings-impl-viewmodel"))
            implementation(project(":app-test-support-rpc"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

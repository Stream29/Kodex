plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-viewmodel")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-working-directory-viewmodel"))
            api(project(":app-component-session-rename-viewmodel"))
            api(project(":app-component-session-delete-viewmodel"))
            api(project(":app-component-openai-login-viewmodel"))
            api(project(":app-component-agent-contract"))
            api(project(":tool-multi-agent"))
            api(project(":app-component-path-picker-viewmodel"))
            api(project(":app-component-session-catalog-viewmodel"))
            api(project(":app-session-contract"))
            api(project(":app-component-new-session-viewmodel"))
            api(project(":app-component-settings-viewmodel"))
            api(project(":app-component-application-preferences-viewmodel"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        commonMain.dependencies {
            api(project(":rpc-local"))
            implementation(project(":app-component-session-settings-viewmodel"))
            implementation(project(":app-component-working-directory-viewmodel"))
            implementation(project(":app-component-session-rename-viewmodel"))
            implementation(project(":app-component-session-delete-viewmodel"))
            implementation(project(":app-component-openai-login-viewmodel"))
            implementation(project(":app-rpc"))
            implementation(project(":hook-notification"))
            implementation(project(":app-session-contract"))
            implementation(project(":app-component-session-catalog-viewmodel"))
            implementation(project(":app-component-history-viewmodel"))
            implementation(project(":app-component-new-session-viewmodel"))
            implementation(project(":app-session"))
            implementation(project(":mcp"))
            implementation(project(":openai-account-usage"))
            implementation(project(":openai-models"))
            implementation(project(":app-component-settings-viewmodel"))
            implementation(project(":app-component-path-picker-viewmodel"))
            implementation(project(":utils-kodex-home"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(project(":utils-logging"))
            implementation(project(":utils-os-environment"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(project(":rpc-server"))
            implementation(project(":app-migration"))
            implementation(project(":app-settings-filesystem"))
            implementation(project(":openai-client"))
            implementation(project(":app-test-support-rpc"))
            implementation(project(":agent-storage-core"))
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":openai-client-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

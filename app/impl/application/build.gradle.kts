plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-viewmodel")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-working-directory-impl-viewmodel"))
            api(project(":app-component-session-rename-impl-viewmodel"))
            api(project(":app-component-session-delete-impl-viewmodel"))
            api(project(":app-component-openai-login-impl-viewmodel"))
            api(project(":app-component-agent-spec"))
            api(project(":tool-multi-agent"))
            api(project(":app-component-path-picker-impl-viewmodel"))
            api(project(":app-component-session-catalog-impl-viewmodel"))
            api(project(":app-spec-session"))
            api(project(":app-component-new-session-impl-viewmodel"))
            api(project(":app-component-settings-impl-viewmodel"))
            api(project(":app-component-application-preferences-impl-viewmodel"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        commonMain.dependencies {
            api(project(":rpc-impl-local"))
            implementation(project(":app-component-session-settings-impl-viewmodel"))
            implementation(project(":app-component-working-directory-impl-viewmodel"))
            implementation(project(":app-component-session-rename-impl-viewmodel"))
            implementation(project(":app-component-session-delete-impl-viewmodel"))
            implementation(project(":app-component-openai-login-impl-viewmodel"))
            implementation(project(":app-impl-rpc"))
            implementation(project(":hook-impl-notification"))
            implementation(project(":app-spec-session"))
            implementation(project(":app-component-session-catalog-impl-viewmodel"))
            implementation(project(":app-component-history-impl-viewmodel"))
            implementation(project(":app-component-new-session-impl-viewmodel"))
            implementation(project(":app-impl-session"))
            implementation(project(":mcp-spec-contract"))
            implementation(project(":openai-account-usage"))
            implementation(project(":openai-spec-models"))
            implementation(project(":app-component-settings-impl-viewmodel"))
            implementation(project(":app-component-path-picker-impl-viewmodel"))
            implementation(project(":utils-kodex-home-spec"))
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(project(":utils-logging-impl"))
            implementation(project(":utils-os-environment-spec"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(project(":rpc-impl-server"))
            implementation(project(":app-migration-impl"))
            implementation(project(":app-settings-impl-filesystem"))
            implementation(project(":openai-client"))
            implementation(project(":app-test-support-rpc"))
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(project(":agent-session-impl-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":openai-client-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

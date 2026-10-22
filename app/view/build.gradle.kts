plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-application"))
            api(project(":app-component-application-preferences-viewmodel"))
            api(project(":utils-external-url"))
            api(libs.mosaic.runtime)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":app-test-support-rpc"))
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-core"))
            implementation(project(":app-component-application-preferences-viewmodel"))
            implementation(project(":app-component-new-session-viewmodel"))
            implementation(project(":app-component-history-viewmodel"))
            implementation(project(":app-session"))
            implementation(libs.kotlinx.coroutines.test)
        }
        mosaicMain.dependencies {
            implementation(project(":app-component-mcp-settings-view"))
            implementation(project(":app-component-composer-view"))
            implementation(project(":app-component-new-session-view"))
            implementation(project(":app-component-session-tab-bar-view"))
            implementation(project(":app-component-runtime-configuration-view"))
            implementation(project(":app-component-session-catalog-view"))
            implementation(project(":app-component-history-index-view"))
            implementation(project(":app-component-request-user-input-view"))
            implementation(project(":app-component-suggest-subagent-task-view"))
            implementation(project(":app-component-working-directory-view"))
            implementation(project(":app-component-working-directory-viewmodel"))
            implementation(project(":app-component-path-picker-viewmodel"))
            implementation(project(":app-component-session-rename-view"))
            implementation(project(":app-component-session-delete-view"))
            implementation(project(":app-component-session-delete-viewmodel"))
            implementation(project(":app-component-openai-login-view"))
            implementation(project(":app-component-agent-contract"))
            implementation(project(":app-component-history-viewmodel"))
            implementation(project(":app-session-contract"))
            implementation(project(":app-component-session-catalog-viewmodel"))
            implementation(project(":app-component-settings-viewmodel"))
            implementation(project(":app-component-application-preferences-viewmodel"))
            implementation(project(":app-component-agent-view"))
            implementation(project(":app-component-session-sidebar-view"))
            implementation(project(":app-view-components"))
            implementation(project(":app-component-history-view"))
            implementation(project(":app-component-settings-view"))
            implementation(project(":openai-models"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-terminal-text"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.mosaic.animation)
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(libs.mosaic.testing)
            implementation(project(":app-migration"))
            implementation(project(":app-settings-filesystem"))
            implementation(project(":rpc-server"))
            implementation(project(":rpc-in-memory"))
            implementation(project(":app-application"))
            implementation(project(":app-component-mcp-settings-viewmodel"))
        }
    }
}

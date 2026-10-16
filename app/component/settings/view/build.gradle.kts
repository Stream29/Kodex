plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-settings-viewmodel"))
        }
        mosaicMain.dependencies {
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
            implementation(project(":app-component-session-rename-view"))
            implementation(project(":app-component-session-rename-viewmodel"))
            implementation(project(":app-view-components"))
            implementation(project(":app-component-working-directory-view"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-test-support-rpc"))
            implementation(project(":app-settings-filesystem"))
            implementation(project(":app-component-session-settings-viewmodel"))
            implementation(project(":app-component-session-title-settings-viewmodel"))
            implementation(project(":app-component-application-preferences-viewmodel"))
            implementation(project(":app-component-authentication-settings-viewmodel"))
            implementation(project(":app-component-settings-viewmodel"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

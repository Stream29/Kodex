plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(project(":app-test-support-rpc"))
            implementation(project(":agent-session-impl-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-spec-contract"))
            implementation(project(":app-shared-auth-contract"))
            implementation(project(":app-shared-settings-contract"))
            implementation(project(":app-viewmodel-new-session"))
            implementation(project(":app-viewmodel-agent"))
            implementation(project(":app-viewmodel-history"))
            implementation(project(":app-viewmodel-session"))
            implementation(libs.kotlinx.coroutines.test)
        }
        mosaicMain.dependencies {
            implementation(project(":app-component-session-rename-impl-view"))
            implementation(project(":app-component-session-delete-impl-view"))
            implementation(project(":app-component-session-delete-impl-viewmodel"))
            implementation(project(":app-component-openai-login-impl-view"))
            api(project(":app-contract-application"))
            implementation(project(":app-contract-agent"))
            implementation(project(":app-contract-history"))
            implementation(project(":app-component-path-picker-spec"))
            implementation(project(":app-contract-session"))
            implementation(project(":app-component-session-catalog-spec"))
            implementation(project(":app-contract-settings"))
            implementation(project(":app-shared-settings-contract"))
            implementation(project(":app-view-agent"))
            implementation(project(":app-view-components"))
            implementation(project(":app-view-history"))
            implementation(project(":app-component-path-picker-impl-view"))
            implementation(project(":app-view-settings"))
            implementation(project(":openai-spec-models"))
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-terminal-text-spec"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.mosaic.animation)
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(libs.mosaic.testing)
        }
    }
}

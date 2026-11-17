plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-application-viewmodel"))
            api(project(":app-rpc-protocol"))
            api(project(":utils-external-url"))
            api(libs.mosaic.runtime)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":app-rpc-test-support"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-core"))
            implementation(project(":app-component-composer-viewmodel"))
            implementation(project(":app-component-history-viewmodel"))
            implementation(libs.kotlinx.coroutines.test)
        }
        mosaicMain.dependencies {
            implementation(project(":app-component-application-view"))
            implementation(project(":app-component-agent-contract"))
            implementation(project(":app-component-agent-view"))
            implementation(project(":app-component-composer-viewmodel"))
            implementation(project(":app-component-history-viewmodel"))
            implementation(project(":app-component-history-index-view"))
            implementation(project(":app-component-path-picker-viewmodel"))
            implementation(project(":app-component-runtime-configuration-viewmodel"))
            implementation(project(":app-component-session-contract"))
            implementation(project(":app-component-session-catalog-viewmodel"))
            implementation(project(":app-component-session-catalog-view"))
            implementation(project(":app-component-settings-viewmodel"))
            implementation(project(":app-component-settings-view"))
            implementation(project(":app-tui-components"))
            implementation(project(":app-tui-renderer"))
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
            implementation(project(":app-backend-migration"))
            implementation(project(":app-backend-settings"))
            implementation(project(":mcp-contract"))
            implementation(project(":rpc-server"))
            implementation(project(":rpc-in-memory"))
            implementation(project(":app-component-application-viewmodel"))
            implementation(project(":app-component-settings-viewmodel"))
        }
    }
}

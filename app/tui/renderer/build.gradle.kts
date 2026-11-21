plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-application-view"))
            api(project(":app-component-session-catalog-view"))
            api(project(":app-tui-components"))
            api(libs.mosaic.runtime)
        }
        named("mosaicMain").dependencies {
            implementation(project(":app-component-widgets"))
            implementation(project(":app-component-path-picker-view"))
            implementation(project(":app-component-settings-view"))
            implementation(project(":app-tui-components"))
            implementation(project(":utils-external-url"))
            implementation(project(":utils-terminal-text"))
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlin.logging)
        }
        named("mosaicTest").dependencies {
            implementation(project(":app-rpc"))
            implementation(project(":app-component-session-contract"))
            implementation(project(":rpc-server"))
            implementation(project(":rpc-in-memory"))
            implementation(project(":app-rpc-test-support"))
            implementation(project(":app-backend-settings"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-core"))
            implementation(project(":utils-coroutines"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

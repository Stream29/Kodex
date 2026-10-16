plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-agent-contract"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(project(":app-component-application-preferences-viewmodel"))
            api(project(":app-component-runtime-configuration-view"))
            api(project(":app-component-suggest-subagent-task-view"))
            implementation(project(":app-component-request-user-input-view"))
            implementation(project(":app-component-history-view"))
            implementation(project(":app-component-composer-view"))
            implementation(libs.mosaic.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
        mosaicTest.dependencies {
            implementation(libs.mosaic.testing)
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":app-test-support-rpc"))
            implementation(project(":app-rpc"))
            implementation(project(":app-session"))
            implementation(project(":app-session-contract"))
            implementation(project(":rpc-server"))
            implementation(project(":rpc-in-memory"))
        }
    }
}

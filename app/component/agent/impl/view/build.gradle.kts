plugins {
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-agent-spec"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(project(":app-component-application-preferences-spec"))
            api(project(":app-component-runtime-configuration-impl-view"))
            api(project(":app-component-suggest-subagent-task-impl-view"))
            implementation(project(":app-component-request-user-input-impl-view"))
            implementation(project(":app-component-history-impl-view"))
            implementation(project(":app-component-composer-impl-view"))
            implementation(libs.mosaic.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
        mosaicTest.dependencies {
            implementation(libs.mosaic.testing)
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":app-test-support-rpc"))
            implementation(project(":app-impl-rpc"))
            implementation(project(":app-impl-session"))
            implementation(project(":app-spec-session"))
            implementation(project(":rpc-impl-server"))
            implementation(project(":rpc-impl-in-memory"))
        }
    }
}

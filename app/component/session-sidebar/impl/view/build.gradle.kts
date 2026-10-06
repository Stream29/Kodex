plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-session-sidebar-spec"))
            api(project(":app-component-agent-spec"))
        }
        mosaicMain.dependencies {
            api(project(":app-view-components"))
            api(project(":app-component-history-index-impl-view"))
            implementation(libs.mosaic.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }
        mosaicTest.dependencies {
            implementation(libs.mosaic.testing)
            implementation(project(":app-test-support-rpc"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

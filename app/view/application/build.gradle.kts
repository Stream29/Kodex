plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-contract"))
            implementation(project(":app-shared-auth-contract"))
            implementation(project(":app-shared-settings-contract"))
            implementation(project(":app-viewmodel-new-session"))
            implementation(project(":app-viewmodel-agent"))
            implementation(project(":app-viewmodel-history"))
            implementation(project(":app-viewmodel-session"))
            implementation(libs.kotlinx.coroutines.test)
        }
        mosaicMain.dependencies {
            api(project(":app-contract-application"))
            implementation(project(":app-contract-agent"))
            implementation(project(":app-contract-history"))
            implementation(project(":app-contract-path-picker"))
            implementation(project(":app-contract-session"))
            implementation(project(":app-contract-session-catalog"))
            implementation(project(":app-contract-settings"))
            implementation(project(":app-shared-settings-contract"))
            implementation(project(":app-view-agent"))
            implementation(project(":app-view-components"))
            implementation(project(":app-view-history"))
            implementation(project(":app-view-path-picker"))
            implementation(project(":app-view-settings"))
            implementation(project(":openai-models"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-terminal-text"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.mosaic.animation)
            implementation(libs.mosaic.runtime)
        }
        desktopMain.dependencies {
            api(project(":app-contract-application"))
            implementation(project(":app-contract-agent"))
            implementation(project(":app-contract-history"))
            implementation(project(":app-contract-path-picker"))
            implementation(project(":app-contract-session"))
            implementation(project(":app-contract-session-catalog"))
            implementation(project(":app-contract-settings"))
            implementation(project(":app-shared-settings-contract"))
            implementation(project(":app-view-agent"))
            implementation(project(":app-view-components"))
            implementation(project(":app-view-history"))
            implementation(project(":app-view-new-session"))
            implementation(project(":app-view-path-picker"))
            implementation(project(":app-view-session"))
            implementation(project(":app-view-settings"))
            implementation(project(":openai-models"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
        mosaicTest.dependencies {
            implementation(libs.mosaic.testing)
        }
    }
}

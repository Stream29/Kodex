plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-contract-settings"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-component-hook-settings-impl-view"))
            implementation(project(":app-component-mcp-settings-impl-view"))
            implementation(project(":app-component-session-rename-impl-view"))
            implementation(project(":app-component-session-rename-impl-viewmodel"))
            implementation(project(":app-view-components"))
            implementation(project(":app-component-working-directory-impl-view"))
            implementation(project(":utils-external-url-impl"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-viewmodel-settings"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

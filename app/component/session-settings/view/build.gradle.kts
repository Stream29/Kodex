plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":app-component-session-settings-viewmodel")) }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(project(":app-component-working-directory-view"))
            implementation(project(":app-component-session-rename-view"))
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-component-session-settings-viewmodel"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

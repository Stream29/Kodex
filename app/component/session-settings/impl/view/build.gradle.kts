plugins { id("kodex.kmp-view"); id("kodex.kmp-tests") }

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":app-component-session-settings-spec")) }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(project(":app-component-working-directory-impl-view"))
            implementation(project(":app-component-session-rename-impl-view"))
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-component-session-settings-impl-viewmodel"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

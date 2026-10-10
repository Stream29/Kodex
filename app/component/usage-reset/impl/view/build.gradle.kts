plugins { id("kodex.kmp-view"); id("kodex.kmp-tests") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-usage-reset-spec"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-component-usage-reset-impl-viewmodel"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

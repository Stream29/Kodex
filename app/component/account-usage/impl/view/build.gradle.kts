plugins { id("kodex.kmp-view") }

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":app-component-account-usage-spec")) }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-component-account-usage-impl-viewmodel"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

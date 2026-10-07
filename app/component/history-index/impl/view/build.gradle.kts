plugins { id("kodex.kmp-view") }

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":app-component-history-index-spec")) }
        mosaicMain.dependencies {
            api(project(":app-view-components"))
            implementation(libs.kotlinx.datetime)
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

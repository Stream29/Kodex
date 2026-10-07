plugins { id("kodex.kmp-view") }

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":app-component-runtime-configuration-spec")) }
        mosaicMain.dependencies {
            api(project(":app-view-components"))
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-component-runtime-configuration-impl-viewmodel"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

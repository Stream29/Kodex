plugins { id("kodex.kmp-view") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-composer-spec"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(project(":app-component-application-preferences-spec"))
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-component-composer-impl-viewmodel"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

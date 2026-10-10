plugins {
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-working-directory-spec"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-component-path-picker-impl-view"))
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-component-working-directory-impl-viewmodel"))
            implementation(project(":app-view-components"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-path-picker-spec"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-component-path-picker-impl-viewmodel"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(libs.mosaic.testing)
        }
    }
}

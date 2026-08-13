plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-contract-path-picker"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-viewmodel-path-picker"))
            implementation(libs.mosaic.testing)
        }
    }
}

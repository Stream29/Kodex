plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-request-user-input-spec"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

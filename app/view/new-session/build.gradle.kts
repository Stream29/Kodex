plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-contract-session"))
        }
        mosaicMain.dependencies {
            implementation(libs.mosaic.runtime)
        }
    }
}

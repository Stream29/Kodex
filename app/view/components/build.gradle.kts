plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        mosaicMain.dependencies {
            api(project(":app-contract-lazy-list"))
            api(libs.mosaic.runtime)
            implementation(project(":utils-terminal-text"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.mosaic.animation)
        }
        mosaicTest.dependencies {
            implementation(libs.mosaic.testing)
        }
    }
}

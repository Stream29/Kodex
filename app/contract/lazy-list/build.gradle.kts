plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.mosaic.runtime)
            api(libs.kotlinx.coroutines.core)
        }
        mosaicTest.dependencies {
            implementation(libs.mosaic.testing)
        }
    }
}

plugins {
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.mosaic.runtime)
            api(libs.kotlinx.coroutines.core)
        }
        mosaicMain.dependencies {
            api(project(":agent-storage-spec-clean-models"))
            api(libs.mosaic.runtime)
            api(libs.kotlinx.datetime)
            api(project(":utils-terminal-text-spec"))
            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            implementation(libs.mosaic.animation)
        }
        mosaicTest.dependencies {
            implementation(libs.mosaic.testing)
        }
    }
}

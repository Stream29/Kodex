plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        mosaicMain.dependencies {
            api(project(":agent-storage-spec-clean-models"))
            api(project(":app-contract-lazy-list"))
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

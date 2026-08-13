plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-storage-clean-models"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(project(":utils-terminal-text"))
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(libs.mosaic.testing)
        }
    }
}

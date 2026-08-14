plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-contract-agent"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(libs.mosaic.runtime)
        }
        desktopMain.dependencies {
            implementation(project(":app-view-components"))
        }
    }
}

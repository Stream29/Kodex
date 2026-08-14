plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-contract-session"))
            implementation(project(":app-view-agent"))
        }
        mosaicMain.dependencies {
            implementation(libs.mosaic.runtime)
        }
        desktopMain.dependencies {
            implementation(project(":app-view-components"))
        }
    }
}

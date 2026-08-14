plugins {
    id("kodex.kmp-view")
    id("org.jetbrains.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-contract-agent"))
            api(project(":app-contract-history"))
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-in-memory"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(project(":app-view-patch"))
            implementation(libs.mosaic.runtime)
        }
        desktopMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(project(":app-view-patch"))
        }
        desktopTest.dependencies {
            implementation(libs.compose.ui.test.junit4)
            implementation(compose.desktop.currentOs)
        }
        mosaicTest.dependencies {
            implementation(libs.mosaic.testing)
        }
    }
}

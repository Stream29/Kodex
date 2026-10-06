plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-session-tab-bar-spec"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(project(":utils-terminal-text-spec"))
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-view-components"))
            implementation(libs.mosaic.testing)
        }
    }
}

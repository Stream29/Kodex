plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-contract-settings"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(project(":app-view-path-picker"))
            implementation(project(":utils-external-url"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.mosaic.runtime)
        }
        desktopMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(project(":app-view-path-picker"))
            implementation(project(":utils-external-url"))
            implementation(libs.kotlinx.coroutines.core)
        }
        mosaicTest.dependencies {
            implementation(project(":app-shared-auth-contract"))
            implementation(project(":app-viewmodel-settings"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

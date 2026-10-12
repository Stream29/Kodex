plugins {
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-new-session-impl-viewmodel"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(project(":app-component-composer-impl-view"))
            implementation(project(":app-component-application-preferences-impl-viewmodel"))
            api(project(":app-component-runtime-configuration-impl-view"))
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(libs.mosaic.testing)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

plugins {
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-session-catalog-spec"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-component-session-delete-impl-view"))
            implementation(project(":app-view-components"))
            implementation(project(":utils-terminal-text-spec"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-component-session-catalog-impl-viewmodel"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

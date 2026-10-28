plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        specMain.dependencies {
            api(project(":app-component-formatting"))
            api(project(":app-component-history-presentation"))
            api(project(":app-component-application-preferences-viewmodel"))
            api(project(":agent-storage-clean-models"))
            api(libs.mosaic.runtime)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        mosaicMain.dependencies {
            api(project(":utils-terminal-text"))
            implementation(libs.mosaic.animation)
        }
        mosaicTest.dependencies {
            implementation(libs.mosaic.testing)
        }
    }
}

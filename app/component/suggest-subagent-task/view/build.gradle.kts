plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-suggest-subagent-task-viewmodel"))
        }
        mosaicMain.dependencies {
            api(project(":app-view-components"))
            implementation(libs.mosaic.runtime)
            implementation(project(":utils-terminal-text"))
        }
        mosaicTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

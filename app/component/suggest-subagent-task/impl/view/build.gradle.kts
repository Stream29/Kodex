plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-suggest-subagent-task-spec"))
        }
        mosaicMain.dependencies {
            api(project(":app-view-components"))
            implementation(libs.mosaic.runtime)
            implementation(project(":utils-terminal-text-spec"))
        }
        mosaicTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

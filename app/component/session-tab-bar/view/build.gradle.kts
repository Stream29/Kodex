plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(libs.kotlinx.coroutines.core)
            api(project(":app-view-components"))
            api(libs.mosaic.runtime)
        }
        mosaicMain.dependencies {
            implementation(project(":utils-terminal-text"))
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-view-components"))
            implementation(libs.mosaic.testing)
        }
    }
}

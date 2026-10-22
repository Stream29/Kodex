plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-request-user-input-viewmodel"))
            api(libs.mosaic.runtime)
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

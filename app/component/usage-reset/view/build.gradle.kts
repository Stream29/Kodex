plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-usage-reset-viewmodel"))
            api(libs.mosaic.runtime)
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-component-usage-reset-viewmodel"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

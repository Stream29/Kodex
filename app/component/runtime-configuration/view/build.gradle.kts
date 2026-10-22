plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-runtime-configuration-viewmodel"))
            api(project(":app-view-components"))
            api(libs.mosaic.runtime)
        }
        mosaicMain.dependencies {
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-component-runtime-configuration-viewmodel"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonMain.dependencies {
            // Compose-generated stability code participates in shared compilations.
            implementation(libs.mosaic.runtime)
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(project(":utils-terminal-text-spec"))
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-view-components"))
            implementation(libs.mosaic.testing)
        }
    }
}

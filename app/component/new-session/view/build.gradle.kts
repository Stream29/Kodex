plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-new-session-viewmodel"))
            api(project(":app-view-components"))
            api(project(":app-component-application-preferences-viewmodel"))
            api(project(":app-component-runtime-configuration-view"))
            api(libs.mosaic.runtime)
        }
        mosaicMain.dependencies {
            implementation(project(":app-component-composer-view"))
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(libs.mosaic.testing)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

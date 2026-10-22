plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-session-settings-viewmodel"))
            api(project(":app-view-components"))
            api(libs.mosaic.runtime)
        }
        mosaicMain.dependencies {
            implementation(project(":app-component-working-directory-view"))
            implementation(project(":app-component-session-rename-view"))
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":app-component-session-settings-viewmodel"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mosaic.testing)
        }
    }
}

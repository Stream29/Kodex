plugins {
    id("kodex.kmp-cli-executable")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":utils-logging-impl"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
        }
        mosaicMain.dependencies {
            implementation(project(":app-migration-impl"))
            implementation(project(":app-impl-view"))
            implementation(project(":app-impl-application"))
            implementation(project(":utils-kodex-home-spec"))
            implementation(project(":utils-logging-impl"))
            implementation(project(":utils-os-environment-spec"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.mosaic.runtime)
        }
    }
}

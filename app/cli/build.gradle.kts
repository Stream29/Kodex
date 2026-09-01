plugins {
    id("kodex.kmp-cli-executable")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":utils-logging"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
        }
        mosaicMain.dependencies {
            implementation(project(":app-migration-impl"))
            implementation(project(":app-view-application"))
            implementation(project(":app-viewmodel-application"))
            implementation(project(":utils-kodex-home"))
            implementation(project(":utils-logging"))
            implementation(project(":utils-os-environment"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.mosaic.runtime)
        }
    }
}

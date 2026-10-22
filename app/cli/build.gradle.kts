plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli-executable")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
        }
        mosaicMain.dependencies {
            implementation(project(":app-backend-host"))
            implementation(project(":rpc-local"))
            implementation(project(":rpc-backend-control-client"))
            implementation(project(":app-backend-daemon"))
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":app-view"))
            implementation(project(":app-application"))
            implementation(project(":utils-kodex-home"))
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(libs.kermit.core)
            implementation(libs.kermit.io)
            implementation(project(":utils-os-environment"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.mosaic.runtime)
        }
    }
}

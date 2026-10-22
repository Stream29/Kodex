plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-agent-contract"))
            api(project(":app-view-components"))
            api(project(":app-component-application-preferences-viewmodel"))
            api(project(":app-component-history-index-view"))
            api(libs.mosaic.runtime)
        }
        commonMain.dependencies {
            api(project(":app-application"))
        }
        mosaicMain.dependencies {
            implementation(libs.mosaic.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }
        mosaicTest.dependencies {
            implementation(libs.mosaic.testing)
            implementation(project(":app-test-support-rpc"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

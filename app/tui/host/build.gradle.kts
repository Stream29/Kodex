plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-application-viewmodel"))
        }
        commonMain.dependencies {
            implementation(libs.kotlin.logging)
            implementation(libs.kermit.core)
            implementation(libs.kermit.io)
            implementation(project(":utils-os-environment"))
            implementation(project(":utils-kotlinx-io-coroutines"))
        }
        named("mosaicMain").dependencies {
            implementation(project(":app-tui"))
            implementation(project(":app-tui-renderer"))
        }
        commonTest.dependencies {
            implementation(project(":app-backend-daemon"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

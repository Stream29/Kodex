plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-view")
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
            implementation(project(":app-tui-path-picker"))
            implementation(project(":hook-notification"))
            implementation(project(":utils-kotlinx-io-coroutines-system"))
        }
        named("mosaicMain").dependencies {
            implementation(project(":app-tui"))
            implementation(project(":app-tui-renderer"))
        }
    }
}

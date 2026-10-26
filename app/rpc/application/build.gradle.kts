plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-frontend")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-application-viewmodel"))
            api(project(":app-rpc"))
            api(project(":rpc-connection"))
            api(libs.kotlinx.coroutines.core)
        }
        commonMain.dependencies {
            implementation(libs.kotlin.logging)
            implementation(project(":app-rpc-session"))
            implementation(project(":app-rpc-new-session"))
            implementation(project(":app-component-session-catalog-viewmodel"))
            implementation(project(":app-component-settings-viewmodel"))
            implementation(project(":app-component-session-settings-viewmodel"))
        }
    }
}

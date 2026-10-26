plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-frontend")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-session-viewmodel"))
            api(project(":app-rpc"))
            api(libs.kotlinx.coroutines.core)
        }
        commonMain.dependencies {
            implementation(project(":utils-coroutines"))
        }
    }
}

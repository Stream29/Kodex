plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":utils-shell-client-impl"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.core)
        }
    }
}

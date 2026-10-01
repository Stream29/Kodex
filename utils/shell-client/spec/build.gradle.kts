plugins {
    id("kodex.kmp-shared")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.serialization.core)
        }
    }
}

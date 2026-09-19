plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

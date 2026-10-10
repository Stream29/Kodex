plugins {
    id("kodex.kmp-cli")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
    }
}

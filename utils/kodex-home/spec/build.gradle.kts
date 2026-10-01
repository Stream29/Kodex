plugins {
    id("kodex.kmp-shared")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.io.core)
        }
    }
}

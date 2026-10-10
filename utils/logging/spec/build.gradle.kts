plugins {
    id("kodex.kmp-shared")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlin.logging)
        }
    }
}

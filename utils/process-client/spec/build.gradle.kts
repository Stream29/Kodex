plugins {
    id("kodex.kmp-shared")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(project(":utils-kotlinx-io-coroutines-spec"))
        }
    }
}

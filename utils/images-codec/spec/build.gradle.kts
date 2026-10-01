plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":utils-images-spec"))
            api(project(":utils-kotlinx-io-coroutines-spec"))
            api(libs.kotlinx.io.core)
        }
    }
}

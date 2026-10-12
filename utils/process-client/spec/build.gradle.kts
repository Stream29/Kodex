plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-shared")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.io.core)
                api(project(":utils-kotlinx-io-coroutines-spec"))
            }
        }
    }
}

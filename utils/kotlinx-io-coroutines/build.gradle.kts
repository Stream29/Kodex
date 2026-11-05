plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-shared")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":utils-coroutines"))
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.io.core)
            }
        }
    }
}

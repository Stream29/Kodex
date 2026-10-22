plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-shared")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(libs.kotlinx.coroutines.core)
        }
    }
}

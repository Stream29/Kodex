plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-shared")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.serialization.core)
        }
    }
}

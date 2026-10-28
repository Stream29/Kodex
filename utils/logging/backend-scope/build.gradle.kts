plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    mingwX64 {
        binaries.all { linkerOpts("-lole32") }
    }
    sourceSets {
        named("specMain").dependencies {
            api(libs.kotlin.logging)
        }
    }
}

plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-frontend")
}

kotlin {
    sourceSets.named("specMain").dependencies {
        api(libs.kotlinx.rpc.core)
        api(libs.kotlinx.coroutines.core)
    }
}

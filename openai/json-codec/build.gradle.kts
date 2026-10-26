plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-frontend")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        // This project exposes a concrete Json policy, not a separate port.
        named("specMain") {
            dependencies {
                api(libs.kotlinx.serialization.json)
            }
        }
    }
}

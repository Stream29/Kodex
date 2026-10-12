plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        // This project exposes a concrete Json policy, not a separate port.
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
    }
}

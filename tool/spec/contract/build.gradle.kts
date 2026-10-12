plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-storage-spec-clean-models"))
                api(project(":openai-spec-models"))
                api(libs.kotlinx.serialization.core)
            }
        }
    }
}

plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-frontend")
    id("kodex.kmp-tests")
    kotlin("plugin.compose")
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":agent-storage-clean-models"))
            api(compose.runtime)
        }
    }
}

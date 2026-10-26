plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-frontend")
    id("kodex.kmp-tests")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets.named("specMain").dependencies {
        api(libs.kotlinx.serialization.json)
        api(project(":openai-models"))
        api(project(":openai-account-usage"))
        api(libs.kotlinx.io.core)
    }
}

plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-spec-models"))
            api(project(":openai-spec-client"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}

plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-spec-models"))
            api(project(":tool-spec-contract"))
            api(libs.kotlinx.serialization.core)
        }
    }
}

plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-models"))
            api(project(":openai-client-contract"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}

plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-spec-account-usage"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.core)
        }
    }
}

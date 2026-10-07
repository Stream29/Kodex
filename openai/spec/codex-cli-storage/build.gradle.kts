plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-spec-models"))
            api(libs.kotlinx.serialization.json)
            api(libs.tomlkt)
        }
    }
}

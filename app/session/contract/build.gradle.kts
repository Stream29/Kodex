plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-agent-contract"))
            api(project(":openai-models"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}

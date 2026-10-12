plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-agent-spec"))
            api(project(":openai-spec-models"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}

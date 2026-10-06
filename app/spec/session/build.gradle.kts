plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-agent-spec"))
            api(project(":openai-spec-models"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}

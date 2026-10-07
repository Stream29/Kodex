plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-session-spec-contract"))
            implementation(project(":agent-state-test"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":openai-spec-model-catalog"))
            implementation(project(":openai-spec-models"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

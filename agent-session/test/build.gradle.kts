plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-session-composition"))
            implementation(project(":agent-state-test"))
            implementation(project(":hook-contract"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-model-catalog-contract"))
            implementation(project(":openai-models"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

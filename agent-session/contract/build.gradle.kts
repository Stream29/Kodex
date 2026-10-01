plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-contract"))
            api(project(":agent-runtime-spec-contract"))
            api(project(":agent-storage-contract"))
            api(project(":hook-contract"))
            api(project(":mcp-spec-contract"))
            api(project(":openai-spec-client"))
            api(project(":openai-spec-model-catalog"))
            api(project(":utils-shell-client-impl"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}

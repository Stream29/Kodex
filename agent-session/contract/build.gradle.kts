plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-contract"))
            api(project(":agent-runtime-contract"))
            api(project(":agent-storage-contract"))
            api(project(":hook-contract"))
            api(project(":mcp-contract"))
            api(project(":openai-client-contract"))
            api(project(":openai-model-catalog-contract"))
            api(project(":utils-shell-client"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}

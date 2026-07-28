plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-contract"))
            api(project(":agent-runtime-contract"))
            api(project(":hook-contract"))
            api(project(":mcp-contract"))
            api(project(":openai-client-contract"))
            api(project(":openai-model-catalog-contract"))
            api(project(":openai-models"))
            api(project(":utils-shell-client"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-runtime-compact"))
            implementation(project(":agent-runtime-plan"))
            implementation(project(":agent-runtime-session-hook"))
            implementation(project(":agent-runtime-tool"))
            implementation(project(":agent-runtime-turn-hook"))
        }
    }
}

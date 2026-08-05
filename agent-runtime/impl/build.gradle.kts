plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-contract"))
            api(project(":agent-session-contract"))
            api(project(":agent-state-contract"))
            implementation(project(":agent-runtime-decorator-compact"))
            implementation(project(":agent-runtime-decorator-steer"))
            implementation(project(":agent-runtime-decorator-subagent"))
            implementation(project(":agent-runtime-decorator-tool"))
            implementation(project(":agent-runtime-decorator-turn-hook"))
            implementation(project(":agent-state-tool"))
            implementation(project(":agent-storage-contract"))
            implementation(project(":hook-contract"))
            implementation(project(":mcp-contract"))
            implementation(project(":openai-client-contract"))
            implementation(project(":openai-model-catalog-contract"))
            implementation(project(":openai-models"))
            implementation(project(":tool-apply-patch"))
            implementation(project(":tool-contract"))
            implementation(project(":tool-current-time"))
            implementation(project(":tool-get-context-remaining"))
            implementation(project(":tool-image-generation-impl"))
            implementation(project(":tool-multi-agent-impl"))
            implementation(project(":tool-plan"))
            implementation(project(":tool-tool-search-impl"))
            implementation(project(":tool-unified-exec-impl"))
            implementation(project(":tool-view-image-impl"))
            implementation(project(":tool-web-run"))
            implementation(project(":utils-kodex-home"))
            implementation(project(":utils-logging"))
            implementation(project(":utils-shell-client"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
    }
}

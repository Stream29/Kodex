plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-contract"))
            api(project(":agent-runtime-composite"))
            api(project(":agent-session-contract"))
            api(project(":hook-contract"))
            api(project(":mcp-contract"))
            api(project(":openai-client-contract"))
            api(project(":openai-model-catalog-contract"))
            api(project(":openai-models"))
            api(project(":agent-storage-contract"))
            api(project(":utils-shell-client"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-state-tool"))
            implementation(project(":agent-runtime-compact"))
            implementation(project(":agent-runtime-contract"))
            implementation(project(":agent-runtime-steer"))
            implementation(project(":agent-runtime-tool"))
            implementation(project(":agent-runtime-turn-hook"))
            implementation(project(":tool-apply-patch"))
            implementation(project(":tool-contract"))
            implementation(project(":tool-current-time"))
            implementation(project(":tool-get-context-remaining"))
            implementation(project(":tool-image-generation"))
            implementation(project(":tool-multi-agent"))
            implementation(project(":tool-plan"))
            implementation(project(":tool-tool-search"))
            implementation(project(":tool-unified-exec"))
            implementation(project(":tool-view-image"))
            implementation(project(":tool-web-run"))
            implementation(project(":utils-codex-lite-home"))
            implementation(libs.kotlinx.io.core)
        }
    }
}

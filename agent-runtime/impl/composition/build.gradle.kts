plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-spec-contract"))
            api(project(":agent-session-contract"))
            api(project(":agent-state-contract"))
            implementation(project(":agent-runtime-impl-decorator-compact"))
            implementation(project(":agent-runtime-impl-decorator-steer"))
            implementation(project(":agent-runtime-impl-decorator-tool"))
            implementation(project(":agent-runtime-impl-decorator-turn-hook"))
            implementation(project(":agent-state-tool"))
            implementation(project(":agent-storage-contract"))
            implementation(project(":hook-contract"))
            implementation(project(":mcp-contract"))
            implementation(project(":openai-spec-client"))
            implementation(project(":openai-spec-model-catalog"))
            implementation(project(":openai-spec-models"))
            implementation(project(":tool-apply-patch"))
            implementation(project(":tool-contract"))
            implementation(project(":tool-current-time"))
            implementation(project(":tool-get-context-remaining"))
            implementation(project(":tool-image-generation-impl"))
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

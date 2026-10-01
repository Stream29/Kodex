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
            implementation(project(":mcp-spec-contract"))
            implementation(project(":openai-spec-client"))
            implementation(project(":openai-spec-model-catalog"))
            implementation(project(":openai-spec-models"))
            implementation(project(":tool-impl-apply-patch"))
            implementation(project(":tool-spec-contract"))
            implementation(project(":tool-impl-current-time"))
            implementation(project(":tool-impl-get-context-remaining"))
            implementation(project(":tool-impl-image-generation"))
            implementation(project(":tool-impl-plan"))
            implementation(project(":tool-impl-tool-search"))
            implementation(project(":tool-impl-unified-exec"))
            implementation(project(":tool-impl-view-image"))
            implementation(project(":tool-impl-web-run"))
            implementation(project(":utils-kodex-home-impl"))
            implementation(project(":utils-logging-impl"))
            implementation(project(":utils-shell-client-impl"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
    }
}

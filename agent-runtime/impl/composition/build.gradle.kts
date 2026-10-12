plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-spec-contract"))
            api(project(":agent-session-spec-contract"))
            api(project(":agent-state-spec-contract"))
            implementation(project(":agent-runtime-decorator-compact"))
            implementation(project(":agent-runtime-steer"))
            implementation(project(":agent-runtime-decorator-tool"))
            implementation(project(":agent-state-impl-state"))
            implementation(project(":agent-storage-core"))
            implementation(project(":mcp-spec-contract"))
            implementation(project(":openai-client"))
            implementation(project(":openai-model-catalog"))
            implementation(project(":openai-spec-models"))
            implementation(project(":tool-apply-patch"))
            implementation(project(":tool-spec-contract"))
            implementation(project(":tool-current-time"))
            implementation(project(":tool-get-context-remaining"))
            implementation(project(":tool-impl-image-generation"))
            implementation(project(":tool-plan"))
            implementation(project(":tool-tool-search"))
            implementation(project(":tool-impl-unified-exec"))
            implementation(project(":tool-impl-view-image"))
            implementation(project(":tool-web-run"))
            implementation(project(":utils-kodex-home-spec"))
            implementation(project(":utils-logging-impl"))
            implementation(project(":utils-shell-client-impl"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
    }
}

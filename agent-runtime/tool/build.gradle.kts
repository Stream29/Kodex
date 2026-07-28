plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-contract"))
            api(project(":agent-runtime-context-window"))
            api(project(":hook-contract"))
            api(project(":mcp-contract"))
            api(project(":openai-client-contract"))
            api(project(":openai-model-catalog"))
            api(project(":tool-contract"))
            api(project(":tool-impl-tool-search"))
            api(project(":utils-shell-client"))
            implementation(project(":hook-tool-utils"))
            implementation(project(":tool-impl-apply-patch"))
            implementation(project(":tool-impl-current-time"))
            implementation(project(":tool-impl-image-generation"))
            implementation(project(":tool-impl-unified-exec"))
            implementation(project(":tool-impl-view-image"))
            implementation(project(":tool-impl-web-run"))
            implementation(project(":tool-spec-get-context-remaining"))
            implementation(project(":tool-tool-builder"))
            implementation(project(":openai-json-codec"))
            implementation(project(":utils-codex-lite-home"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":agent-runtime-compact"))
            implementation(project(":agent-runtime-turn-hook"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client-test"))
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
        }
    }
}

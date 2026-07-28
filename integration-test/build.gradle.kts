plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(project(":agent-context-prefix-render"))
            implementation(project(":agent-runtime-compact"))
            implementation(project(":agent-runtime-plan"))
            implementation(project(":agent-runtime-tool"))
            implementation(project(":agent-runtime-turn-hook"))
            implementation(project(":agent-session-composition"))
            implementation(project(":agent-session-filesystem"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-contract"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":hook-contract"))
            implementation(project(":hook-impl"))
            implementation(project(":openai-client"))
            implementation(project(":openai-client-contract"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-codex-cli-storage"))
            implementation(project(":openai-json-codec"))
            implementation(project(":openai-model-catalog-impl"))
            implementation(project(":tool-impl-apply-patch"))
            implementation(project(":tool-impl-current-time"))
            implementation(project(":tool-impl-image-generation"))
            implementation(project(":tool-spec-plan"))
            implementation(project(":tool-spec-request-user-input"))
            implementation(project(":tool-impl-view-image"))
            implementation(project(":tool-impl-web-run"))
            implementation(project(":tool-impl-tool-search"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-os-environment"))
            implementation(project(":utils-shell-client"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(project(":agent-runtime-tool"))
            implementation(project(":mcp-impl"))
            implementation(libs.kotlinx.schema.json)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.core)
            implementation(libs.mcp.kotlin.sdk.server)
        }
    }
}

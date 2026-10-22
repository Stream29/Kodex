plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-runtime-contract"))
                api(project(":agent-session-contract"))
                api(project(":agent-state-contract"))
            }
        }
        commonMain.dependencies {
            implementation(project(":agent-runtime-decorator-compact"))
            implementation(project(":agent-runtime-steer"))
            implementation(project(":agent-runtime-decorator-tool"))
            implementation(project(":agent-state-core"))
            implementation(project(":agent-storage-core"))
            implementation(project(":mcp-contract"))
            implementation(project(":openai-client"))
            implementation(project(":openai-model-catalog"))
            implementation(project(":openai-models"))
            implementation(project(":tool-apply-patch"))
            implementation(project(":tool-core"))
            implementation(project(":tool-current-time"))
            implementation(project(":tool-get-context-remaining"))
            implementation(project(":tool-image-generation"))
            implementation(project(":tool-plan"))
            implementation(project(":tool-tool-search-backend"))
            implementation(project(":tool-unified-exec"))
            implementation(project(":tool-view-image"))
            implementation(project(":tool-web-run"))
            implementation(project(":utils-kodex-home"))
            implementation(project(":utils-logging-backend-scope"))
            implementation(project(":utils-shell-client-system"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
    }
}

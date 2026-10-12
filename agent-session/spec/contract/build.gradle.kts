plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-context-spec-contract"))
                api(project(":agent-runtime-spec-contract"))
                api(project(":agent-storage-core"))
                api(project(":mcp-spec-contract"))
                api(project(":openai-client"))
                api(project(":openai-model-catalog"))
                api(project(":utils-shell-client-spec"))
                api(libs.kotlinx.coroutines.core)
            }
        }
    }
}

plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-context-settings"))
                api(project(":agent-runtime-contract"))
                api(project(":agent-storage-core"))
                api(project(":mcp"))
                api(project(":openai-client"))
                api(project(":openai-model-catalog"))
                api(project(":utils-shell-client"))
                api(libs.kotlinx.coroutines.core)
            }
        }
    }
}

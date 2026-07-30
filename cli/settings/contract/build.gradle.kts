plugins {
    id("codexlite.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-contract"))
            api(project(":hook-contract"))
            api(project(":mcp-contract"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(project(":openai-models"))
            api(project(":utils-shell-client"))
        }
    }
}

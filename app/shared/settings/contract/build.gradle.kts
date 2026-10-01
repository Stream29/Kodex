plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-contract"))
            api(project(":hook-contract"))
            api(project(":mcp-spec-contract"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(project(":openai-spec-models"))
            api(project(":utils-shell-client-impl"))
        }
    }
}

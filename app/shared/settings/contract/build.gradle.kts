plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-spec-contract"))
            api(project(":hook-spec-hooks"))
            api(project(":mcp-spec-contract"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(project(":openai-spec-models"))
            api(project(":utils-shell-client-impl"))
        }
    }
}

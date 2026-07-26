plugins {
    id("codexlite.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-prefix-contract"))
            api(project(":hook-contract"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(project(":openai-models"))
            api(project(":utils-shell-client"))
        }
    }
}

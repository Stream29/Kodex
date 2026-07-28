plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-contract"))
            api(project(":openai-models"))
            api(project(":openai-model-catalog-contract"))
            api(project(":tool-contract"))
            implementation(project(":agent-runtime-context-window"))
            implementation(project(":tool-tool-builder"))
            implementation(libs.kotlinx.schema.json)
            implementation(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-codex-cli-storage"))
            implementation(project(":openai-json-codec"))
            implementation(project(":openai-model-catalog-impl"))
            implementation(project(":utils-coroutines"))
        }
    }
}

plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-contract"))
            api(project(":openai-spec-models"))
            api(project(":openai-spec-model-catalog"))
            api(project(":tool-contract"))
            api(libs.kotlinx.schema.json)
            implementation(project(":agent-state-context-window"))
            implementation(project(":tool-tool-builder"))
            implementation(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":openai-impl-model-catalog"))
            implementation(project(":utils-coroutines"))
        }
    }
}

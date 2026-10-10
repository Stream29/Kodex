plugins {
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-spec-get-context-remaining"))
            api(project(":agent-state-spec-contract"))
            api(project(":openai-spec-models"))
            api(project(":openai-spec-model-catalog"))
            api(project(":tool-spec-contract"))
            api(libs.kotlinx.schema.json)
            implementation(project(":agent-state-spec-context-window"))
            implementation(project(":tool-impl-builder"))
            implementation(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-state-impl-state"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-impl-in-memory"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":openai-impl-model-catalog"))
            implementation(project(":utils-coroutines-spec"))
        }
    }
}

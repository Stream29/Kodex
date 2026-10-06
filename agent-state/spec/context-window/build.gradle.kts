plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-spec-contract"))
            api(project(":openai-spec-models"))
            api(project(":openai-spec-model-catalog"))
            implementation(project(":agent-storage-spec-contract"))
        }
        commonTest.dependencies {
            implementation(project(":agent-state-impl-state"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-impl-in-memory"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":openai-impl-model-catalog"))
            implementation(project(":utils-coroutines-spec"))
        }
    }
}

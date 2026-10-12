plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-spec-contract"))
            api(project(":openai-spec-models"))
            api(project(":openai-model-catalog"))
            implementation(project(":agent-storage-core"))
        }
        commonTest.dependencies {
            implementation(project(":agent-state-impl-state"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-core"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-model-catalog"))
            implementation(project(":utils-coroutines-spec"))
        }
    }
}

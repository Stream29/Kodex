plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-contract"))
            api(project(":openai-models"))
            api(project(":openai-model-catalog-contract"))
            implementation(project(":agent-storage-contract"))
        }
        commonTest.dependencies {
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-model-catalog-impl"))
        }
    }
}

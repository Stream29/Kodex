plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-contract"))
            api(project(":openai-model-catalog"))
        }
        commonTest.dependencies {
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client-test"))
        }
    }
}

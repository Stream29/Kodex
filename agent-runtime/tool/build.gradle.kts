plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-contract"))
            api(project(":agent-runtime-context-window"))
            api(project(":openai-model-catalog"))
            api(project(":tool-contract"))
            api(project(":tool-tool-search"))
            implementation(project(":tool-impl-get-context-remaining"))
            implementation(project(":openai-json-codec"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":agent-runtime-compact"))
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client-test"))
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
        }
    }
}

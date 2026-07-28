plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-contract"))
            api(project(":openai-models"))
            api(project(":tool-contract"))
            implementation(project(":tool-tool-builder"))
            implementation(libs.kotlinx.schema.json)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-json-codec"))
        }
    }
}

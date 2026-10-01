plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-spec-plan"))
            api(project(":agent-state-contract"))
            api(project(":openai-spec-models"))
            api(project(":tool-spec-contract"))
            api(libs.kotlinx.schema.json)
            implementation(project(":tool-impl-builder"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-impl-in-memory"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":utils-coroutines-spec"))
        }
    }
}

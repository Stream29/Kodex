plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":openai-spec-models"))
                api(project(":tool-spec-contract"))
                api(libs.kotlinx.schema.json)
            }
        }
        commonMain.dependencies {
            api(project(":agent-state-spec-contract"))
            api(project(":openai-model-catalog"))
            implementation(project(":agent-state-spec-context-window"))
            implementation(project(":tool-builder"))
            implementation(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-state-impl-state"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-core"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":openai-model-catalog"))
            implementation(project(":utils-coroutines-spec"))
        }
    }
}

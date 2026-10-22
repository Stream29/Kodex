plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":openai-models"))
                api(project(":tool-core"))
                api(project(":agent-state-contract"))
                api(libs.kotlinx.schema.json)
            }
        }
        commonMain.dependencies {
            implementation(project(":tool-core"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":agent-state-core"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-core"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-json-codec"))
            implementation(project(":utils-coroutines"))
        }
    }
}

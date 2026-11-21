plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-state-contract"))
                api(project(":agent-context-settings"))
                api(project(":agent-storage-core"))
                api(project(":openai-client"))
                api(project(":openai-model-catalog"))
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonMain.dependencies {
            implementation(project(":agent-context"))
            implementation(project(":tool-apply-patch"))
            implementation(project(":tool-await-event"))
            implementation(project(":tool-intent-name"))
            implementation(project(":tool-read-media"))
            implementation(project(":tool-plan"))
            implementation(project(":tool-unified-exec"))
            implementation(project(":openai-json-codec"))
            implementation(project(":utils-coroutines"))
        }
        commonTest.dependencies {
            implementation(project(":agent-context"))
            implementation(project(":agent-storage-core"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client-test"))
            implementation(project(":utils-kotlinx-io-coroutines"))
        }
    }
}

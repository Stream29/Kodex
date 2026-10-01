plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-contract"))
            api(project(":agent-context-contract"))
            api(project(":agent-storage-contract-ext"))
            api(project(":mcp-spec-contract"))
            api(project(":openai-spec-client"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-context-prefix-impl"))
            implementation(project(":agent-context-prefix-render"))
            implementation(project(":agent-state-tool"))
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":utils-coroutines-spec"))
        }
        commonTest.dependencies {
            implementation(project(":agent-context-prefix-render"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":tool-impl-current-time"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

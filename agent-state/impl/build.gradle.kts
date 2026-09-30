plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-contract"))
            api(project(":agent-context-contract"))
            api(project(":agent-storage-contract-ext"))
            api(project(":mcp-contract"))
            api(project(":openai-spec-client"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-context-prefix-impl"))
            implementation(project(":agent-context-prefix-render"))
            implementation(project(":agent-state-tool"))
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":utils-coroutines"))
        }
        commonTest.dependencies {
            implementation(project(":agent-context-prefix-render"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":tool-current-time"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

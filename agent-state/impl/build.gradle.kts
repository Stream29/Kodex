plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-contract"))
            api(project(":agent-context-contract"))
            api(project(":mcp-contract"))
            implementation(project(":agent-context-prefix-impl"))
            implementation(project(":agent-context-prefix-render"))
            implementation(project(":agent-state-tool"))
            implementation(project(":openai-client-contract"))
            implementation(project(":openai-json-codec"))
            implementation(project(":utils-coroutines"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-context-prefix-render"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client-test"))
            implementation(project(":tool-impl-current-time"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

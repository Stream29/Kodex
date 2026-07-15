plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-contract"))
            api(project(":agent-context-prefix-contract"))
            implementation(project(":agent-context-collaboration-render"))
            implementation(project(":agent-context-prefix-render"))
            implementation(project(":openai-client-contract"))
            implementation(project(":openai-json-codec"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

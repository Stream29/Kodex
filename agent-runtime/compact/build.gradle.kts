plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-contract"))
            api(project(":openai-model-catalog"))
            implementation(project(":agent-runtime-context-window"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-context-collaboration-render"))
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

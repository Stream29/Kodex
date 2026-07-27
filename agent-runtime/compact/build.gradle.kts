plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-contract"))
            api(project(":hook-contract"))
            api(project(":openai-model-catalog"))
            implementation(project(":agent-runtime-context-window"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-context-prefix-render"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-contract"))
            api(project(":hook-contract"))
            api(project(":openai-model-catalog-contract"))
            api(libs.kotlin.logging)
            implementation(project(":agent-state-context-window"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-context-prefix-render"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-model-catalog-impl"))
            implementation(project(":tool-tool-search-impl"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

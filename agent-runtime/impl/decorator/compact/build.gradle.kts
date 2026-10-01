plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-spec-decorator-compact"))
            api(project(":agent-runtime-spec-contract"))
            api(project(":hook-contract"))
            api(project(":openai-spec-model-catalog"))
            api(libs.kotlin.logging)
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-state-context-window"))
        }
        commonTest.dependencies {
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":openai-impl-model-catalog"))
            implementation(project(":utils-coroutines-spec"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

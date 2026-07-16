plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-contract"))
            implementation(project(":tool-contract"))
            implementation(project(":tool-impl-plan"))
            implementation(project(":tool-tool-builder"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-runtime-compact"))
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-json-codec"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

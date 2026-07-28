plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-contract"))
            api(project(":hook-contract"))
            implementation(project(":hook-tool-utils"))
            implementation(project(":tool-contract"))
            implementation(project(":tool-spec-plan"))
            implementation(project(":tool-tool-builder"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":agent-runtime-compact"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-json-codec"))
            implementation(project(":openai-model-catalog-impl"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

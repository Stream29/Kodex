plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-multi-agent-contract"))
            api(project(":agent-session-contract"))
            api(project(":agent-state-contract"))
            api(project(":openai-models"))
            api(project(":tool-contract"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.schema.json)
            implementation(project(":agent-storage-contract"))
            implementation(project(":tool-tool-builder"))
        }
        commonTest.dependencies {
            implementation(project(":openai-json-codec"))
        }
    }
}

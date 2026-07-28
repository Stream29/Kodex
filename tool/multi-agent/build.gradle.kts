plugins {
    id("codexlite.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-session-contract"))
            api(project(":agent-state-contract"))
            api(project(":openai-models"))
            api(project(":tool-contract"))
            implementation(project(":agent-storage-contract"))
            implementation(project(":tool-tool-builder"))
            implementation(libs.kotlinx.schema.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(project(":openai-json-codec"))
        }
    }
}

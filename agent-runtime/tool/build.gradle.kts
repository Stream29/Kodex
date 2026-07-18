plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-contract"))
            api(project(":tool-contract"))
            api(project(":tool-tool-search"))
            implementation(project(":openai-json-codec"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

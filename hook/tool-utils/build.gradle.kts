plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-storage-contract"))
            api(project(":hook-contract"))
            implementation(project(":openai-json-codec"))
        }
    }
}

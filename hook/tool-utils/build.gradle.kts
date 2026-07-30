plugins {
    id("kodex.kmp-host")
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

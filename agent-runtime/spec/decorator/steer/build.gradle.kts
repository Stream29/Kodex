plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-spec-contract"))
            api(project(":agent-storage-clean-models"))
        }
    }
}

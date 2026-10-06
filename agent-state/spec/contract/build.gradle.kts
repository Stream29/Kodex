plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(project(":agent-storage-spec-contract"))
            api(project(":agent-storage-spec-clean-models"))
            api(project(":openai-spec-models"))
        }
    }
}

plugins {
    id("kodex.kmp-cli")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-storage-spec-clean-models"))
            api(project(":agent-storage-spec-contract"))
            api(project(":agent-state-spec-contract"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}

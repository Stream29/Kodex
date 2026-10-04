plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-contract-lazy-list"))
            api(project(":agent-storage-spec-clean-models"))
            api(project(":agent-storage-spec-contract"))
            api(project(":agent-state-contract"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}

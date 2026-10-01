plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-contract-lazy-list"))
            api(project(":agent-storage-spec-clean-models"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}

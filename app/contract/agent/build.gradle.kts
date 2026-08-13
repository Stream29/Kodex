plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-storage-clean-models"))
            api(project(":app-contract-history"))
            api(project(":openai-models"))
            api(project(":tool-request-user-input-contract"))
            api(project(":tool-unified-exec-contract"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
    }
}

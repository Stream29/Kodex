plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-storage-spec-clean-models"))
            api(project(":app-contract-history"))
            api(project(":openai-spec-models"))
            api(project(":rpc-spec-models"))
            api(project(":tool-spec-request-user-input"))
            api(project(":tool-spec-multi-agent"))
            api(project(":tool-spec-unified-exec"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
    }
}

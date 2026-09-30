plugins {
    id("kodex.kmp-cli")
}

// Test fixtures only. No production module may depend on this module.
kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":app-viewmodel-rpc"))
        api(project(":app-viewmodel-session"))
        api(project(":app-viewmodel-new-session"))
        api(project(":openai-impl-client-test"))
        implementation(project(":rpc-server"))
        implementation(project(":rpc-in-memory"))
        implementation(project(":app-migration-impl"))
        implementation(project(":utils-kotlinx-io-coroutines"))
        implementation(project(":utils-coroutines"))
        implementation(project(":agent-session-filesystem"))
        implementation(project(":agent-session-test"))
        implementation(project(":agent-storage-contract-ext"))
    }
}

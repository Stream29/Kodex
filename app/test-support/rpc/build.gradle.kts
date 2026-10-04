plugins {
    id("kodex.kmp-cli")
}

// Test fixtures only. No production module may depend on this module.
kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":app-viewmodel-rpc"))
        api(project(":app-viewmodel-session"))
        api(project(":app-component-new-session-impl-viewmodel"))
        api(project(":openai-impl-client-test"))
        implementation(project(":rpc-impl-server"))
        implementation(project(":rpc-impl-in-memory"))
        implementation(project(":app-migration-impl"))
        implementation(project(":utils-kotlinx-io-coroutines-impl"))
        implementation(project(":utils-coroutines-spec"))
        implementation(project(":agent-session-impl-filesystem"))
        implementation(project(":agent-session-test"))
        implementation(project(":agent-storage-spec-contract-ext"))
    }
}

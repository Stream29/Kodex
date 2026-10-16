plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli")
}

// Test fixtures only. No production module may depend on this module.
kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":app-application"))
        api(project(":rpc-local"))
        implementation(project(":hook-notification"))
        implementation(project(":openai-client"))
        api(project(":app-rpc"))
        api(project(":app-session"))
        api(project(":app-component-new-session-viewmodel"))
        api(project(":openai-client-test"))
        implementation(project(":rpc-server"))
        implementation(project(":rpc-in-memory"))
        implementation(project(":app-migration"))
        implementation(project(":utils-kotlinx-io-coroutines-system"))
        implementation(project(":utils-coroutines"))
        implementation(project(":utils-os-environment"))
        implementation(project(":agent-session-filesystem"))
        implementation(project(":agent-session-test"))
        implementation(project(":agent-storage-core"))
    }
}

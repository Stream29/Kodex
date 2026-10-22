plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli")
}

// Test fixtures only. No production module may depend on this module.
kotlin {
    sourceSets.named("specMain").dependencies {
        api(project(":app-application"))
        api(project(":app-rpc"))
        api(project(":app-session"))
        api(project(":app-component-new-session-viewmodel"))
        api(project(":openai-models"))
        api(project(":agent-storage-clean-models"))
        api(project(":app-migration"))
        api(project(":openai-client"))
        api(project(":utils-os-environment"))
        api(libs.kotlinx.coroutines.core)
        api(libs.kotlinx.io.core)
    }
    sourceSets.commonMain.dependencies {
        api(project(":rpc-local"))
        api(project(":openai-client-test"))
        implementation(project(":hook-notification"))
        implementation(project(":rpc-server"))
        implementation(project(":rpc-in-memory"))
        implementation(project(":utils-kotlinx-io-coroutines-system"))
        implementation(project(":utils-coroutines"))
        implementation(project(":agent-session-filesystem"))
        implementation(project(":agent-session-test"))
        implementation(project(":agent-storage-core"))
    }
}

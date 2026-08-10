plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-contract"))
            api(project(":agent-session-contract"))
            api(project(":app-shared-session-title"))
            api(project(":tool-request-user-input-contract"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-storage-clean-models"))
            implementation(project(":utils-coroutines"))
        }
    }
}

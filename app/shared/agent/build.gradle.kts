plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-contract"))
            api(project(":agent-session-contract"))
            api(project(":tool-request-user-input-contract"))
            implementation(project(":app-shared-session-title"))
            implementation(project(":agent-storage-clean-models"))
            implementation(project(":utils-coroutines"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

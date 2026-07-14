plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(project(":agent-runtime-impl"))
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-storage-contract"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client"))
            implementation(project(":openai-client-contract"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-codex-cli-storage"))
            implementation(project(":utils-os-environment"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

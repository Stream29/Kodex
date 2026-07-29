plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-session-contract"))
            implementation(project(":agent-runtime-impl"))
            implementation(project(":agent-session-multi-agent"))
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":utils-coroutines"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-session-test"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-json-codec"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

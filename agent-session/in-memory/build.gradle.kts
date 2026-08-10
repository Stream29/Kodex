plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-session-contract"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-runtime-impl"))
            implementation(project(":agent-session-multi-agent"))
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":utils-coroutines"))
        }
        commonTest.dependencies {
            implementation(project(":agent-session-test"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-json-codec"))
            implementation(project(":tool-multi-agent-impl"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

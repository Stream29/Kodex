plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-session-contract"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-runtime-impl-composition"))
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":utils-coroutines"))
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-contract-ext"))
            implementation(project(":agent-session-test"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":openai-spec-json-codec"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

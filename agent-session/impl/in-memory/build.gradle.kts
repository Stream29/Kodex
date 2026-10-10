plugins {
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-session-spec-contract"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-runtime-impl-composition"))
            implementation(project(":agent-state-impl-state"))
            implementation(project(":agent-storage-impl-in-memory"))
            implementation(project(":utils-coroutines-spec"))
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(project(":agent-session-test"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":openai-spec-json-codec"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

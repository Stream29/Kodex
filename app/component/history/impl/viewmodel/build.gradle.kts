plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-viewmodel")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":agent-storage-spec-clean-models"))
            api(project(":agent-storage-core"))
            api(project(":agent-state-spec-contract"))
            api(libs.kotlinx.coroutines.core)
        }
        commonMain.dependencies {
            implementation(libs.kotlin.logging)
            implementation(project(":agent-state-spec-contract"))
            implementation(project(":agent-storage-core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-rpc-exception-spec"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":agent-session-impl-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(project(":agent-storage-core"))
        }
    }
}

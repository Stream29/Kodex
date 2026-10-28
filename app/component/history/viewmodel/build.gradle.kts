plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-frontend")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":agent-storage-clean-models"))
            api(project(":agent-storage-core"))
            api(project(":agent-state-contract"))
            api(libs.kotlinx.coroutines.core)
        }
        commonMain.dependencies {
            implementation(libs.kotlin.logging)
            implementation(project(":agent-state-contract"))
            implementation(project(":agent-storage-core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-rpc-exception"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":openai-json-codec"))
            implementation(project(":agent-storage-core"))
        }
        nativeTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
        }
    }
}

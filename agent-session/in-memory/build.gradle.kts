plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-session-contract"))
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonMain.dependencies {
            implementation(project(":agent-runtime-composition"))
            implementation(project(":agent-state-core"))
            implementation(project(":agent-storage-core"))
            implementation(project(":utils-coroutines"))
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-core"))
            implementation(project(":agent-session-test"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-json-codec"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

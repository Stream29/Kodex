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
                api(project(":utils-kotlinx-io-coroutines-system"))
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonMain.dependencies {
            implementation(project(":agent-runtime-composition"))
            implementation(project(":agent-state-core"))
            implementation(project(":agent-storage-filesystem"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-filesystem-lease"))
            implementation(project(":utils-read-write-mutex"))
            implementation(libs.cache4k)
        }
        commonTest.dependencies {
            implementation(project(":openai-client-test"))
            implementation(project(":agent-storage-core"))
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

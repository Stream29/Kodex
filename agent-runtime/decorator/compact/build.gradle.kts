plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-runtime-spec-contract"))
            }
        }
        commonMain.dependencies {
            api(project(":agent-runtime-spec-contract"))
            api(project(":agent-runtime-steer"))
            api(project(":openai-model-catalog"))
            api(libs.kotlin.logging)
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-state-spec-context-window"))
        }
        commonTest.dependencies {
            implementation(project(":agent-runtime-decorator-tool"))
            implementation(project(":agent-runtime-steer"))
            implementation(project(":agent-state-impl-state"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-core"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-model-catalog"))
            implementation(project(":utils-coroutines-spec"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

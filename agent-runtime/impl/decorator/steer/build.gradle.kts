plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-spec-decorator-steer"))
            api(project(":agent-runtime-spec-contract"))
            api(libs.kotlin.logging)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":utils-coroutines-spec"))
        }
    }
}
